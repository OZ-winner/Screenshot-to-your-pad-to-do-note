use crate::{
    capture::{PendingPreview, SelectionRatios},
    protocol::{AppStatus, PublicDevice, ServerMessage},
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
    pub screenshot_tx: broadcast::Sender<ServerMessage>,
    pub pending_capture: Option<PendingCapture>,
    pub remote_selection: Option<SelectionRatios>,
    pub capture_sequence: u64,
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
        }
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
