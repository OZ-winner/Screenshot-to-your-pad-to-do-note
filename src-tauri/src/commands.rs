use crate::{
    bridge::{save_devices, PendingCapture, SharedBridge},
    capture::{
        build_screenshot_artifact, capture_primary_jpeg, capture_primary_raw, crop_rgba_to_png,
        preview_from_rgba, PendingPreview, ScreenshotArtifact, ScreenshotFormat, SelectionRatios,
        SelectionRect,
    },
    media::execute_media_command,
    protocol::{
        AppStatus, BridgeStatus, CaptureNotice, CaptureNoticePhase, ClientMessage, RemoteCommand,
        RemoteSelectionPayload, RemoteSelectionPhase, ScreenshotResultPayload, ServerMessage,
    },
};
use anyhow::{anyhow, Context, Result};
use futures_util::{SinkExt, StreamExt};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tauri::{
    AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, State, WebviewUrl,
    WebviewWindowBuilder,
};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::{accept_async, tungstenite::Message};

#[tauri::command]
pub fn get_app_status(state: State<'_, SharedBridge>) -> AppStatus {
    state.lock().expect("bridge state poisoned").status()
}

#[tauri::command]
pub async fn start_bridge_server(
    app: AppHandle,
    state: State<'_, SharedBridge>,
    port: Option<u16>,
) -> Result<AppStatus, String> {
    spawn_bridge_server(app.clone(), state.inner().clone(), port)
        .await
        .map_err(|error| error.to_string())?;
    Ok(state.lock().expect("bridge state poisoned").status())
}

pub async fn spawn_bridge_server(
    app: AppHandle,
    state: SharedBridge,
    port: Option<u16>,
) -> Result<()> {
    {
        let mut guard = state.lock().expect("bridge state poisoned");
        if guard.server_running {
            return Ok(());
        }
        if let Some(port) = port {
            guard.port = port;
        }
    }

    let port = state.lock().expect("bridge state poisoned").port;
    let listener = TcpListener::bind(("0.0.0.0", port))
        .await
        .with_context(|| format!("failed to bind WebSocket server on port {}", port))?;

    {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.server_running = true;
    }
    let _ = app.emit("bridge-status", ());

    tauri::async_runtime::spawn(async move {
        while let Ok((stream, _addr)) = listener.accept().await {
            let app = app.clone();
            let state = state.clone();
            tauri::async_runtime::spawn(async move {
                let _ = handle_client(app, state, stream).await;
            });
        }
    });

    Ok(())
}

async fn handle_client(app: AppHandle, state: SharedBridge, stream: TcpStream) -> Result<()> {
    let ws = accept_async(stream).await?;
    let (mut write, mut read) = ws.split();
    let mut screenshot_rx = {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.connected_clients += 1;
        guard.screenshot_tx.subscribe()
    };
    let _ = app.emit("bridge-status", ());

    let hello = {
        let guard = state.lock().expect("bridge state poisoned");
        ServerMessage::PairingReady {
            server_name: gethostname::gethostname().to_string_lossy().to_string(),
            code: guard.pairing_code.clone(),
        }
    };
    write
        .send(Message::Text(serde_json::to_string(&hello)?))
        .await?;

    let mut authenticated = false;

    loop {
        tokio::select! {
            maybe_message = read.next() => {
                let Some(message) = maybe_message else { break; };
                let message = message?;
                if !message.is_text() {
                    continue;
                }
                let response = handle_client_message(&app, &state, &message.into_text()?, &mut authenticated).await;
                match response {
                    Ok(Some(response)) => {
                        write.send(Message::Text(serde_json::to_string(&response)?)).await?;
                    }
                    Ok(None) => {}
                    Err(error) => {
                        let response = ServerMessage::Error { message: error.to_string() };
                        write.send(Message::Text(serde_json::to_string(&response)?)).await?;
                    }
                }
            }
            broadcast = screenshot_rx.recv(), if authenticated => {
                if let Ok(artifact) = broadcast {
                    write.send(Message::Text(serde_json::to_string(&artifact.metadata)?)).await?;
                    write.send(Message::Binary(artifact.bytes.as_ref().clone())).await?;
                }
            }
        }
    }

    {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.connected_clients = guard.connected_clients.saturating_sub(1);
    }
    let _ = app.emit("bridge-status", ());
    Ok(())
}

async fn handle_client_message(
    app: &AppHandle,
    state: &SharedBridge,
    raw: &str,
    authenticated: &mut bool,
) -> Result<Option<ServerMessage>> {
    let message: ClientMessage = serde_json::from_str(raw).context("invalid client message")?;
    match message {
        ClientMessage::Pair(payload) => {
            let device = {
                let mut guard = state.lock().expect("bridge state poisoned");
                if payload.code != guard.pairing_code {
                    return Err(anyhow!("pairing code is incorrect"));
                }
                let device = guard.pair_device(payload.device_name);
                guard.regenerate_code();
                save_devices(app, &guard)?;
                device
            };
            *authenticated = true;
            let _ = app.emit("bridge-status", ());
            Ok(Some(ServerMessage::Paired {
                token: device.token,
                device_id: device.id,
            }))
        }
        ClientMessage::Command(payload) => {
            {
                let mut guard = state.lock().expect("bridge state poisoned");
                if !guard.touch_token(&payload.token) {
                    return Err(anyhow!("device token is not paired"));
                }
                save_devices(app, &guard)?;
            }
            *authenticated = true;

            match payload.command {
                RemoteCommand::Screenshot => {
                    let revision = begin_capture_notice(app, state, "正在截取全屏...");
                    spawn_full_screenshot(app.clone(), state.clone(), revision);
                    Ok(Some(ServerMessage::Status {
                        status: BridgeStatus::Saving,
                        message: "screenshot processing".to_string(),
                    }))
                }
                command => {
                    execute_media_command(command)?;
                    Ok(Some(ServerMessage::Status {
                        status: BridgeStatus::Connected,
                        message: "command sent".to_string(),
                    }))
                }
            }
        }
        ClientMessage::RemoteSelection(payload) => {
            {
                let mut guard = state.lock().expect("bridge state poisoned");
                if !guard.touch_token(&payload.token) {
                    return Err(anyhow!("device token is not paired"));
                }
            }
            *authenticated = true;
            let phase = payload.phase.clone();
            handle_remote_selection(app, state, payload).await?;
            match phase {
                RemoteSelectionPhase::Update => Ok(None),
                RemoteSelectionPhase::Confirm => Ok(Some(ServerMessage::Status {
                    status: BridgeStatus::Saving,
                    message: "screenshot processing".to_string(),
                })),
                _ => Ok(Some(ServerMessage::Status {
                    status: BridgeStatus::Connected,
                    message: "remote selection updated".to_string(),
                })),
            }
        }
        ClientMessage::ScreenshotResult(payload) => {
            handle_screenshot_result(app, state, payload, authenticated)?;
            Ok(Some(ServerMessage::Status {
                status: BridgeStatus::Connected,
                message: "screenshot result received".to_string(),
            }))
        }
        ClientMessage::Ping { token } => {
            if let Some(token) = token {
                let mut guard = state.lock().expect("bridge state poisoned");
                *authenticated = guard.touch_token(&token);
            }
            Ok(Some(ServerMessage::Pong))
        }
    }
}

fn spawn_full_screenshot(app: AppHandle, state: SharedBridge, notice_revision: u64) {
    tauri::async_runtime::spawn(async move {
        let result = tokio::task::spawn_blocking(|| {
            let (jpeg, width, height) = capture_primary_jpeg()?;
            build_screenshot_artifact(jpeg, width, height, ScreenshotFormat::Jpeg)
        })
        .await
        .context("screen capture task failed")
        .and_then(|result| result);

        match result {
            Ok(artifact) => send_screenshot_artifact(&app, &state, artifact, notice_revision),
            Err(error) => publish_capture_failure_for_revision(
                &app,
                &state,
                notice_revision,
                format!("截图失败：{error}"),
            ),
        }
    });
}

async fn handle_remote_selection(
    app: &AppHandle,
    state: &SharedBridge,
    payload: RemoteSelectionPayload,
) -> Result<()> {
    match payload.phase {
        RemoteSelectionPhase::Begin => {
            begin_screenshot_capture(app, state, Some(&payload)).await?;
            hide_remote_outline(app);
        }
        RemoteSelectionPhase::Update => {
            if has_pending_capture(state) {
                set_remote_selection(state, &payload, true);
                schedule_remote_outline_update(app, state);
            }
        }
        RemoteSelectionPhase::Confirm => {
            set_remote_selection(state, &payload, true);
            hide_remote_outline(app);
            confirm_remote_selection(app, state, payload)?;
        }
        RemoteSelectionPhase::Cancel => {
            clear_pending_selection(state);
            hide_remote_outline(app);
        }
    }
    Ok(())
}

async fn begin_screenshot_capture(
    app: &AppHandle,
    state: &SharedBridge,
    remote_payload: Option<&RemoteSelectionPayload>,
) -> Result<()> {
    clear_pending_selection(state);
    hide_overlay_window(app);
    hide_remote_outline(app);
    let _ = app.emit("screenshot-preview-reset", ());

    let capture = tokio::task::spawn_blocking(capture_primary_raw)
        .await
        .context("screen capture task failed")??;
    let pixels = Arc::new(capture.pixels);
    let capture_id = {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.capture_sequence = guard.capture_sequence.wrapping_add(1);
        let capture_id = guard.capture_sequence;
        guard.remote_selection = remote_payload.map(|payload| SelectionRatios {
            x_ratio: payload.x_ratio,
            y_ratio: payload.y_ratio,
            width_ratio: payload.width_ratio,
            height_ratio: payload.height_ratio,
        });
        guard.pending_capture = Some(PendingCapture {
            id: capture_id,
            pixels: pixels.clone(),
            width: capture.width,
            height: capture.height,
            preview: None,
        });
        capture_id
    };

    if remote_payload.is_none() {
        let _ = app.emit("screenshot-preview-reset", ());
        show_overlay_window(app)?;
        let _ = app.emit("screenshot-preview-updated", ());
        spawn_preview_encoding(app.clone(), state.clone(), capture_id, pixels);
    }
    Ok(())
}

fn spawn_preview_encoding(
    app: AppHandle,
    state: SharedBridge,
    capture_id: u64,
    pixels: Arc<image::RgbaImage>,
) {
    tauri::async_runtime::spawn(async move {
        let preview = tokio::task::spawn_blocking(move || preview_from_rgba(&pixels)).await;
        let Ok(Ok(preview)) = preview else {
            return;
        };
        let should_emit = {
            let mut guard = state.lock().expect("bridge state poisoned");
            if let Some(pending) = guard.pending_capture.as_mut() {
                if pending.id == capture_id {
                    pending.preview = Some(preview);
                    true
                } else {
                    false
                }
            } else {
                false
            }
        };
        if should_emit {
            let _ = app.emit("screenshot-preview-updated", ());
        }
    });
}

fn has_pending_capture(state: &SharedBridge) -> bool {
    state
        .lock()
        .expect("bridge state poisoned")
        .pending_capture
        .is_some()
}

fn clear_pending_selection(state: &SharedBridge) {
    let mut guard = state.lock().expect("bridge state poisoned");
    guard.pending_capture = None;
    guard.remote_selection = None;
}

fn hide_overlay_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("overlay") {
        let _ = window.hide();
    }
}

fn hide_remote_outline(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("remote-outline") {
        let _ = window.hide();
    }
}

fn schedule_remote_outline_update(app: &AppHandle, state: &SharedBridge) {
    let now = Instant::now();
    let (render_now, selection, trailing_delay) = {
        let mut guard = state.lock().expect("bridge state poisoned");
        let elapsed = guard
            .outline_last_render
            .map(|last| now.saturating_duration_since(last))
            .unwrap_or(Duration::from_millis(16));

        if elapsed >= Duration::from_millis(16) {
            guard.outline_last_render = Some(now);
            (true, guard.remote_selection.clone(), None)
        } else if !guard.outline_update_scheduled {
            guard.outline_update_scheduled = true;
            (
                false,
                None,
                Some(Duration::from_millis(16).saturating_sub(elapsed)),
            )
        } else {
            (false, None, None)
        }
    };

    if render_now {
        if let Some(selection) = selection {
            let _ = render_remote_outline(app, &selection);
        }
    }

    if let Some(delay) = trailing_delay {
        let app = app.clone();
        let state = state.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(delay).await;
            let selection = {
                let mut guard = state.lock().expect("bridge state poisoned");
                guard.outline_update_scheduled = false;
                guard.outline_last_render = Some(Instant::now());
                guard.remote_selection.clone()
            };
            if let Some(selection) = selection {
                let _ = render_remote_outline(&app, &selection);
            }
        });
    }
}

fn render_remote_outline(app: &AppHandle, selection: &SelectionRatios) -> Result<()> {
    precreate_remote_windows(app)?;
    let main = app
        .get_webview_window("main")
        .ok_or_else(|| anyhow!("main window is unavailable"))?;
    let monitor = main
        .primary_monitor()?
        .ok_or_else(|| anyhow!("primary monitor is unavailable"))?;
    let monitor_size = monitor.size();
    let monitor_position = monitor.position();
    let rect = selection.to_rect(monitor_size.width, monitor_size.height);

    if selection.width_ratio <= 0.0 || selection.height_ratio <= 0.0 {
        hide_remote_outline(app);
        return Ok(());
    }

    if let Some(window) = app.get_webview_window("remote-outline") {
        window.set_size(PhysicalSize::new(rect.width.max(8), rect.height.max(8)))?;
        window.set_position(PhysicalPosition::new(
            monitor_position.x + rect.x as i32,
            monitor_position.y + rect.y as i32,
        ))?;
        window.show()?;
    }
    Ok(())
}

fn set_remote_selection(state: &SharedBridge, payload: &RemoteSelectionPayload, active: bool) {
    let mut guard = state.lock().expect("bridge state poisoned");
    guard.remote_selection = active.then_some(SelectionRatios {
        x_ratio: payload.x_ratio,
        y_ratio: payload.y_ratio,
        width_ratio: payload.width_ratio,
        height_ratio: payload.height_ratio,
    });
}

fn confirm_remote_selection(
    app: &AppHandle,
    state: &SharedBridge,
    payload: RemoteSelectionPayload,
) -> Result<()> {
    let pending = take_pending_capture(state).ok_or_else(|| anyhow!("no pending screenshot"))?;
    let ratios = SelectionRatios {
        x_ratio: payload.x_ratio,
        y_ratio: payload.y_ratio,
        width_ratio: payload.width_ratio,
        height_ratio: payload.height_ratio,
    };
    let rect = ratios.to_rect(pending.width, pending.height);
    let revision = begin_capture_notice(app, state, "正在处理截图...");
    spawn_cropped_screenshot(app.clone(), state.clone(), pending, rect, revision);
    Ok(())
}

#[tauri::command]
pub fn regenerate_pairing_code(state: State<'_, SharedBridge>) -> AppStatus {
    let mut guard = state.lock().expect("bridge state poisoned");
    guard.regenerate_code();
    guard.status()
}

#[tauri::command]
pub fn clear_devices(app: AppHandle, state: State<'_, SharedBridge>) -> Result<AppStatus, String> {
    let mut guard = state.lock().expect("bridge state poisoned");
    guard.paired_devices.clear();
    save_devices(&app, &guard).map_err(|error| error.to_string())?;
    Ok(guard.status())
}

#[tauri::command]
pub fn send_media_command(command: RemoteCommand) -> Result<(), String> {
    execute_media_command(command).map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn open_screenshot_overlay(app: AppHandle) -> Result<(), String> {
    let bridge = app.state::<SharedBridge>();
    begin_screenshot_capture(&app, bridge.inner(), None)
        .await
        .map_err(|error| error.to_string())?;
    Ok(())
}

pub fn precreate_overlay_window(app: &AppHandle) -> Result<()> {
    if app.get_webview_window("overlay").is_some() {
        return Ok(());
    }

    WebviewWindowBuilder::new(app, "overlay", WebviewUrl::App("overlay.html".into()))
        .title("选择截图区域")
        .transparent(true)
        .decorations(false)
        .always_on_top(true)
        .fullscreen(true)
        .skip_taskbar(true)
        .visible(false)
        .build()?;
    Ok(())
}

pub fn precreate_remote_windows(app: &AppHandle) -> Result<()> {
    precreate_overlay_window(app)?;

    if app.get_webview_window("remote-outline").is_none() {
        let window = WebviewWindowBuilder::new(
            app,
            "remote-outline",
            WebviewUrl::App("selection-outline.html".into()),
        )
        .title("截图选区")
        .transparent(true)
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .shadow(false)
        .resizable(false)
        .focused(false)
        .inner_size(8.0, 8.0)
        .visible(false)
        .build()?;
        window.set_ignore_cursor_events(true)?;
        window.set_content_protected(true)?;
    }

    if app.get_webview_window("capture-toast").is_none() {
        let window = WebviewWindowBuilder::new(
            app,
            "capture-toast",
            WebviewUrl::App("capture-toast.html".into()),
        )
        .title("截图状态")
        .transparent(true)
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .shadow(false)
        .resizable(false)
        .focused(false)
        .inner_size(360.0, 56.0)
        .visible(false)
        .build()?;
        window.set_ignore_cursor_events(true)?;
        window.set_content_protected(true)?;
    }

    Ok(())
}

fn show_overlay_window(app: &AppHandle) -> Result<()> {
    precreate_overlay_window(app)?;
    if let Some(window) = app.get_webview_window("overlay") {
        window.set_ignore_cursor_events(false)?;
        window.show()?;
        window.set_focus()?;
    }
    Ok(())
}

#[tauri::command]
pub fn get_pending_preview(state: State<'_, SharedBridge>) -> Result<PendingPreview, String> {
    let guard = state.lock().expect("bridge state poisoned");
    let pending = guard
        .pending_capture
        .clone()
        .ok_or_else(|| "no pending screenshot".to_string())?;
    pending
        .preview
        .ok_or_else(|| "screenshot preview is still preparing".to_string())
}

#[tauri::command]
pub async fn confirm_screenshot_selection(
    app: AppHandle,
    state: State<'_, SharedBridge>,
    rect: SelectionRect,
) -> Result<(), String> {
    let pending =
        take_pending_capture(state.inner()).ok_or_else(|| "no pending screenshot".to_string())?;
    hide_overlay_window(&app);
    let revision = begin_capture_notice(&app, state.inner(), "正在处理截图...");
    spawn_cropped_screenshot(app, state.inner().clone(), pending, rect, revision);
    Ok(())
}

fn take_pending_capture(state: &SharedBridge) -> Option<PendingCapture> {
    let mut guard = state.lock().expect("bridge state poisoned");
    guard.remote_selection = None;
    guard.pending_capture.take()
}

fn spawn_cropped_screenshot(
    app: AppHandle,
    state: SharedBridge,
    pending: PendingCapture,
    rect: SelectionRect,
    notice_revision: u64,
) {
    tauri::async_runtime::spawn(async move {
        let result = tokio::task::spawn_blocking(move || {
            let (png, width, height) = crop_rgba_to_png(&pending.pixels, rect)?;
            build_screenshot_artifact(png, width, height, ScreenshotFormat::Png)
        })
        .await
        .context("screenshot encode task failed")
        .and_then(|result| result);

        match result {
            Ok(artifact) => send_screenshot_artifact(&app, &state, artifact, notice_revision),
            Err(error) => publish_capture_failure_for_revision(
                &app,
                &state,
                notice_revision,
                format!("截图失败：{error}"),
            ),
        }
    });
}

fn send_screenshot_artifact(
    app: &AppHandle,
    state: &SharedBridge,
    artifact: ScreenshotArtifact,
    notice_revision: u64,
) {
    let tx = {
        let mut guard = state.lock().expect("bridge state poisoned");
        let is_current = guard.capture_notice.as_ref().is_some_and(|notice| {
            notice.revision == notice_revision && notice.phase == CaptureNoticePhase::Processing
        });
        if !is_current {
            return;
        }
        guard.register_delivery(artifact.id.clone(), notice_revision);
        guard.screenshot_tx.clone()
    };

    if tx.send(artifact.clone()).is_err() {
        state
            .lock()
            .expect("bridge state poisoned")
            .acknowledge_delivery(&artifact.id);
        publish_capture_failure_for_revision(
            app,
            state,
            notice_revision,
            "截图发送失败：没有已连接的平板",
        );
    }
}

fn handle_screenshot_result(
    app: &AppHandle,
    state: &SharedBridge,
    payload: ScreenshotResultPayload,
    authenticated: &mut bool,
) -> Result<()> {
    let matched_revision = {
        let mut guard = state.lock().expect("bridge state poisoned");
        if !guard.touch_token(&payload.token) {
            return Err(anyhow!("device token is not paired"));
        }
        *authenticated = true;
        guard.acknowledge_delivery(&payload.id)
    };

    let Some(matched_revision) = matched_revision else {
        return Ok(());
    };

    if payload.success {
        publish_capture_notice_for_revision(
            app,
            state,
            matched_revision,
            CaptureNoticePhase::Success,
            "截图已保存到平板",
        );
    } else {
        let message = payload
            .message
            .filter(|message| !message.trim().is_empty())
            .unwrap_or_else(|| "平板保存截图失败".to_string());
        publish_capture_notice_for_revision(
            app,
            state,
            matched_revision,
            CaptureNoticePhase::Failed,
            message,
        );
    }
    Ok(())
}

fn begin_capture_notice(app: &AppHandle, state: &SharedBridge, message: &str) -> u64 {
    state
        .lock()
        .expect("bridge state poisoned")
        .abandon_deliveries();
    let notice = publish_capture_notice(app, state, CaptureNoticePhase::Processing, message, false);
    let revision = notice.revision;
    let app = app.clone();
    let state = state.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(15)).await;
        let timed_out = {
            let mut guard = state.lock().expect("bridge state poisoned");
            let is_current = guard.capture_notice.as_ref().is_some_and(|notice| {
                notice.revision == revision && notice.phase == CaptureNoticePhase::Processing
            });
            if is_current {
                guard.expire_deliveries(revision);
            }
            is_current
        };
        if timed_out {
            publish_capture_failure_for_revision(&app, &state, revision, "截图超时，请重试");
        }
    });
    revision
}

fn publish_capture_failure_for_revision(
    app: &AppHandle,
    state: &SharedBridge,
    expected_revision: u64,
    message: impl Into<String>,
) {
    publish_capture_notice_for_revision(
        app,
        state,
        expected_revision,
        CaptureNoticePhase::Failed,
        message,
    );
}

fn publish_capture_notice_for_revision(
    app: &AppHandle,
    state: &SharedBridge,
    expected_revision: u64,
    phase: CaptureNoticePhase,
    message: impl Into<String>,
) {
    let notice = state
        .lock()
        .expect("bridge state poisoned")
        .transition_capture_notice(expected_revision, phase, message);
    if let Some(notice) = notice {
        display_capture_notice(app, state, notice, true);
    }
}

fn publish_capture_notice(
    app: &AppHandle,
    state: &SharedBridge,
    phase: CaptureNoticePhase,
    message: impl Into<String>,
    auto_hide: bool,
) -> CaptureNotice {
    let notice = state
        .lock()
        .expect("bridge state poisoned")
        .set_capture_notice(phase, message);
    display_capture_notice(app, state, notice.clone(), auto_hide);
    notice
}

fn display_capture_notice(
    app: &AppHandle,
    state: &SharedBridge,
    notice: CaptureNotice,
    auto_hide: bool,
) {
    let _ = render_capture_notice(app, &notice);
    let _ = app.emit("capture-notice", notice.clone());
    let _ = app.emit("bridge-status", ());

    if auto_hide {
        let revision = notice.revision;
        let app = app.clone();
        let state = state.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(Duration::from_secs(3)).await;
            let cleared = state
                .lock()
                .expect("bridge state poisoned")
                .clear_capture_notice(revision);
            if cleared {
                hide_capture_toast(&app);
                let _ = app.emit("capture-notice-cleared", revision);
                let _ = app.emit("bridge-status", ());
            }
        });
    }
}

fn render_capture_notice(app: &AppHandle, notice: &CaptureNotice) -> Result<()> {
    precreate_remote_windows(app)?;
    let main = app
        .get_webview_window("main")
        .ok_or_else(|| anyhow!("main window is unavailable"))?;
    let monitor = main
        .primary_monitor()?
        .ok_or_else(|| anyhow!("primary monitor is unavailable"))?;
    let width = 360i32;
    let x = monitor.position().x + ((monitor.size().width as i32 - width) / 2).max(0);
    let y = monitor.position().y + 24;

    if let Some(window) = app.get_webview_window("capture-toast") {
        window.set_position(PhysicalPosition::new(x, y))?;
        let _ = window.emit("capture-notice", notice.clone());
        window.show()?;
    }
    Ok(())
}

fn hide_capture_toast(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("capture-toast") {
        let _ = window.hide();
    }
}
