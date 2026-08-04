use crate::{
    capture::{PendingPreview, ScreenshotArtifact, SelectionRatios},
    protocol::{AppStatus, CaptureNotice, CaptureNoticePhase, PublicDevice},
};
use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use image::RgbaImage;
use rand::{distributions::Alphanumeric, Rng};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tauri::{AppHandle, Manager};
use tokio::sync::broadcast;

pub type SharedBridge = Arc<Mutex<BridgeState>>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    pub token: String,
    pub last_seen: DateTime<Utc>,
}

#[derive(Clone)]
pub struct BridgeState {
    pub port: u16,
    pub server_running: bool,
    pub pairing_code: String,
    pub paired_devices: HashMap<String, PairedDevice>,
    pub connected_clients: usize,
    pub screenshot_tx: broadcast::Sender<ScreenshotArtifact>,
    pub pending_capture: Option<PendingCapture>,
    pub remote_selection: Option<SelectionRatios>,
    pub capture_sequence: u64,
    pub capture_notice: Option<CaptureNotice>,
    pub capture_notice_sequence: u64,
    pub pending_deliveries: HashMap<String, u64>,
    pub outline_last_render: Option<std::time::Instant>,
    pub outline_update_scheduled: bool,
}

impl Default for BridgeState {
    fn default() -> Self {
        let (screenshot_tx, _) = broadcast::channel(32);
        Self {
            port: 46731,
            server_running: false,
            pairing_code: generate_pairing_code(),
            paired_devices: HashMap::new(),
            connected_clients: 0,
            screenshot_tx,
            pending_capture: None,
            remote_selection: None,
            capture_sequence: 0,
            capture_notice: None,
            capture_notice_sequence: 0,
            pending_deliveries: HashMap::new(),
            outline_last_render: None,
            outline_update_scheduled: false,
        }
    }
}

#[derive(Clone)]
pub struct PendingCapture {
    pub id: u64,
    pub pixels: Arc<RgbaImage>,
    pub width: u32,
    pub height: u32,
    pub preview: Option<PendingPreview>,
}

impl BridgeState {
    pub fn status(&self) -> AppStatus {
        let local_ip = local_ip_address::local_ip()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|_| "127.0.0.1".to_string());
        AppStatus {
            server_running: self.server_running,
            port: self.port,
            local_ip: local_ip.clone(),
            pairing_code: self.pairing_code.clone(),
            pairing_url: format!(
                "ws://{}:{}/bridge?code={}",
                local_ip, self.port, self.pairing_code
            ),
            connected_clients: self.connected_clients,
            paired_devices: self
                .paired_devices
                .values()
                .map(|device| PublicDevice {
                    id: device.id.clone(),
                    name: device.name.clone(),
                    last_seen: device.last_seen.to_rfc3339(),
                })
                .collect(),
            capture_notice: self.capture_notice.clone(),
        }
    }

    pub fn set_capture_notice(
        &mut self,
        phase: CaptureNoticePhase,
        message: impl Into<String>,
    ) -> CaptureNotice {
        self.capture_notice_sequence = self.capture_notice_sequence.wrapping_add(1);
        let notice = CaptureNotice {
            revision: self.capture_notice_sequence,
            phase,
            message: message.into(),
        };
        self.capture_notice = Some(notice.clone());
        notice
    }

    pub fn clear_capture_notice(&mut self, revision: u64) -> bool {
        if self.capture_notice.as_ref().map(|notice| notice.revision) == Some(revision) {
            self.capture_notice = None;
            true
        } else {
            false
        }
    }

    pub fn register_delivery(&mut self, id: String, notice_revision: u64) {
        self.pending_deliveries.insert(id, notice_revision);
    }

    pub fn acknowledge_delivery(&mut self, id: &str) -> Option<u64> {
        self.pending_deliveries.remove(id)
    }

    pub fn abandon_deliveries(&mut self) {
        self.pending_deliveries.clear();
    }

    pub fn transition_capture_notice(
        &mut self,
        expected_revision: u64,
        phase: CaptureNoticePhase,
        message: impl Into<String>,
    ) -> Option<CaptureNotice> {
        let is_current = self.capture_notice.as_ref().is_some_and(|notice| {
            notice.revision == expected_revision && notice.phase == CaptureNoticePhase::Processing
        });
        if !is_current {
            return None;
        }

        self.expire_deliveries(expected_revision);
        Some(self.set_capture_notice(phase, message))
    }

    pub fn expire_deliveries(&mut self, notice_revision: u64) {
        self.pending_deliveries
            .retain(|_, revision| *revision != notice_revision);
    }

    pub fn regenerate_code(&mut self) -> String {
        self.pairing_code = generate_pairing_code();
        self.pairing_code.clone()
    }

    pub fn pair_device(&mut self, name: String) -> PairedDevice {
        let token = random_token(32);
        let device = PairedDevice {
            id: uuid::Uuid::new_v4().to_string(),
            name,
            token,
            last_seen: Utc::now(),
        };
        self.paired_devices
            .insert(device.token.clone(), device.clone());
        device
    }

    pub fn touch_token(&mut self, token: &str) -> bool {
        if let Some(device) = self.paired_devices.get_mut(token) {
            device.last_seen = Utc::now();
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::BridgeState;
    use crate::protocol::CaptureNoticePhase;

    #[test]
    fn screenshot_delivery_requires_a_matching_id() {
        let mut state = BridgeState::default();
        let notice = state.set_capture_notice(CaptureNoticePhase::Processing, "processing");
        state.register_delivery("capture-1".to_string(), notice.revision);

        assert_eq!(state.acknowledge_delivery("unknown"), None);
        assert_eq!(
            state.acknowledge_delivery("capture-1"),
            Some(notice.revision)
        );
        assert_eq!(state.acknowledge_delivery("capture-1"), None);
    }

    #[test]
    fn stale_notice_cannot_clear_a_newer_notice() {
        let mut state = BridgeState::default();
        let first = state.set_capture_notice(CaptureNoticePhase::Processing, "first");
        let second = state.set_capture_notice(CaptureNoticePhase::Success, "second");

        assert!(!state.clear_capture_notice(first.revision));
        assert!(state.clear_capture_notice(second.revision));
    }

    #[test]
    fn stale_result_cannot_replace_a_newer_processing_notice() {
        let mut state = BridgeState::default();
        let first = state.set_capture_notice(CaptureNoticePhase::Processing, "first");
        let second = state.set_capture_notice(CaptureNoticePhase::Processing, "second");

        assert!(
            state
                .transition_capture_notice(
                    first.revision,
                    CaptureNoticePhase::Success,
                    "stale success",
                )
                .is_none()
        );
        assert_eq!(state.capture_notice, Some(second));
    }
}

pub fn generate_pairing_code() -> String {
    rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(6)
        .map(char::from)
        .collect::<String>()
        .to_uppercase()
}

fn random_token(len: usize) -> String {
    rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(len)
        .map(char::from)
        .collect()
}

pub fn config_path(app: &AppHandle) -> Result<PathBuf> {
    let dir = app
        .path()
        .app_config_dir()
        .context("failed to resolve app config directory")?;
    Ok(dir.join("bridge-devices.json"))
}

pub fn load_devices(app: &AppHandle, state: &mut BridgeState) -> Result<()> {
    let path = config_path(app)?;
    if !path.exists() {
        return Ok(());
    }
    let content = fs::read_to_string(&path)?;
    let devices: Vec<PairedDevice> = serde_json::from_str(&content)?;
    state.paired_devices = devices
        .into_iter()
        .map(|device| (device.token.clone(), device))
        .collect();
    Ok(())
}

pub fn save_devices(app: &AppHandle, state: &BridgeState) -> Result<()> {
    let path = config_path(app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let devices: Vec<_> = state.paired_devices.values().cloned().collect();
    fs::write(path, serde_json::to_string_pretty(&devices)?)?;
    Ok(())
}
