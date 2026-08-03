use crate::{
    bridge::{save_devices, PendingCapture, SharedBridge},
    capture::{
        build_screenshot_message, capture_primary_png, capture_primary_raw, crop_rgba_to_png,
        preview_from_rgba, PendingPreview, SelectionRatios, SelectionRect,
    },
    media::execute_media_command,
    protocol::{
        AppStatus, BridgeStatus, ClientMessage, RemoteCommand, RemoteSelectionPayload,
        RemoteSelectionPhase, ServerMessage,
    },
};
use anyhow::{anyhow, Context, Result};
use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::{accept_async, tungstenite::Message};

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteSelectionEvent {
    active: bool,
    x_ratio: f64,
    y_ratio: f64,
    width_ratio: f64,
    height_ratio: f64,
}

#[tauri::command]
pub fn get_app_status(state: State<'_, SharedBridge>) -> AppStatus {
    state.lock().expect("bridge state poisoned").status()
}

#[tauri::command]
pub fn get_remote_selection(state: State<'_, SharedBridge>) -> Option<RemoteSelectionEvent> {
    state
        .lock()
        .expect("bridge state poisoned")
        .remote_selection
        .clone()
        .map(|selection| RemoteSelectionEvent {
            active: true,
            x_ratio: selection.x_ratio,
            y_ratio: selection.y_ratio,
            width_ratio: selection.width_ratio,
            height_ratio: selection.height_ratio,
        })
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
                if let Ok(message) = broadcast {
                    write.send(Message::Text(serde_json::to_string(&message)?)).await?;
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
                    broadcast_full_screenshot(app, state).await?;
                    Ok(Some(ServerMessage::Status {
                        status: BridgeStatus::Saved,
                        message: "screenshot captured".to_string(),
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
            handle_remote_selection(app, state, payload).await?;
            Ok(Some(ServerMessage::Status {
                status: BridgeStatus::Connected,
                message: "remote selection updated".to_string(),
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

async fn broadcast_full_screenshot(app: &AppHandle, state: &SharedBridge) -> Result<()> {
    let (png, width, height) = capture_primary_png()?;
    let artifact = build_screenshot_message(png, width, height)?;
    let tx = state
        .lock()
        .expect("bridge state poisoned")
        .screenshot_tx
        .clone();
    let _ = tx.send(artifact.message);
    let _ = app.emit("screenshot-sent", "全屏截图已发送到平板");
    Ok(())
}

async fn handle_remote_selection(
    app: &AppHandle,
    state: &SharedBridge,
    payload: RemoteSelectionPayload,
) -> Result<()> {
    match payload.phase {
        RemoteSelectionPhase::Begin => {
            begin_screenshot_capture(app, state, Some(&payload)).await?;
            emit_remote_selection(app, &payload, true)?;
        }
        RemoteSelectionPhase::Update => {
            if has_pending_capture(state) {
                set_remote_selection(state, &payload, true);
                emit_remote_selection(app, &payload, true)?;
            }
        }
        RemoteSelectionPhase::Confirm => {
            set_remote_selection(state, &payload, true);
            emit_remote_selection(app, &payload, true)?;
            confirm_remote_selection(app, state, payload).await?;
        }
        RemoteSelectionPhase::Cancel => {
            clear_pending_selection(state);
            emit_remote_selection(app, &payload, false)?;
            hide_overlay_window(app);
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

    let _ = app.emit("screenshot-preview-reset", ());
    show_overlay_window(app)?;
    let _ = app.emit("screenshot-preview-updated", ());
    spawn_preview_encoding(app.clone(), state.clone(), capture_id, pixels);
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

fn emit_remote_selection(
    app: &AppHandle,
    payload: &RemoteSelectionPayload,
    active: bool,
) -> Result<()> {
    app.emit(
        "remote-selection",
        RemoteSelectionEvent {
            active,
            x_ratio: payload.x_ratio,
            y_ratio: payload.y_ratio,
            width_ratio: payload.width_ratio,
            height_ratio: payload.height_ratio,
        },
    )?;
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

async fn confirm_remote_selection(
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
    finish_screenshot_selection(app, state, pending, rect).await?;
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

fn show_overlay_window(app: &AppHandle) -> Result<()> {
    precreate_overlay_window(app)?;
    if let Some(window) = app.get_webview_window("overlay") {
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
    finish_screenshot_selection(&app, state.inner(), pending, rect)
        .await
        .map_err(|error| error.to_string())
}

fn take_pending_capture(state: &SharedBridge) -> Option<PendingCapture> {
    let mut guard = state.lock().expect("bridge state poisoned");
    guard.remote_selection = None;
    guard.pending_capture.take()
}

async fn finish_screenshot_selection(
    app: &AppHandle,
    state: &SharedBridge,
    pending: PendingCapture,
    rect: SelectionRect,
) -> Result<()> {
    hide_overlay_window(app);
    let artifact = tokio::task::spawn_blocking(move || {
        let (png, width, height) = crop_rgba_to_png(&pending.pixels, rect)?;
        build_screenshot_message(png, width, height)
    })
    .await
    .context("screenshot encode task failed")??;
    let tx = state
        .lock()
        .expect("bridge state poisoned")
        .screenshot_tx
        .clone();
    let _ = tx.send(artifact.message);
    let _ = app.emit("screenshot-sent", "截图已发送到平板");
    Ok(())
}
