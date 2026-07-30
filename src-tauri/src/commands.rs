use crate::{
    bridge::{save_devices, SharedBridge},
    capture::{
        build_screenshot_message, capture_primary_png, crop_png, preview_from_png, PendingPreview,
        SelectionRatios, SelectionRect,
    },
    media::execute_media_command,
    protocol::{
        AppStatus, BridgeStatus, ClientMessage, RemoteCommand, RemoteSelectionPayload,
        RemoteSelectionPhase, ServerMessage,
    },
};
use anyhow::{anyhow, Context, Result};
use futures_util::{SinkExt, StreamExt};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::{accept_async, tungstenite::Message};

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct RemoteSelectionEvent {
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
                    broadcast_full_screenshot(state).await?;
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
                save_devices(app, &guard)?;
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

async fn broadcast_full_screenshot(state: &SharedBridge) -> Result<()> {
    let (png, width, height) = capture_primary_png()?;
    let artifact = build_screenshot_message(png, width, height)?;
    let tx = state
        .lock()
        .expect("bridge state poisoned")
        .screenshot_tx
        .clone();
    let _ = tx.send(artifact.message);
    Ok(())
}

async fn handle_remote_selection(
    app: &AppHandle,
    state: &SharedBridge,
    payload: RemoteSelectionPayload,
) -> Result<()> {
    match payload.phase {
        RemoteSelectionPhase::Begin => {
            prepare_remote_selection(app, state)?;
            emit_remote_selection(app, &payload, true)?;
        }
        RemoteSelectionPhase::Update => {
            ensure_remote_selection_ready(app, state)?;
            emit_remote_selection(app, &payload, true)?;
        }
        RemoteSelectionPhase::Confirm => {
            ensure_remote_selection_ready(app, state)?;
            emit_remote_selection(app, &payload, true)?;
            confirm_remote_selection(app, state, payload)?;
        }
        RemoteSelectionPhase::Cancel => {
            {
                let mut guard = state.lock().expect("bridge state poisoned");
                guard.pending_capture = None;
            }
            emit_remote_selection(app, &payload, false)?;
            if let Some(window) = app.get_webview_window("overlay") {
                let _ = window.hide();
            }
        }
    }
    Ok(())
}

fn prepare_remote_selection(app: &AppHandle, state: &SharedBridge) -> Result<()> {
    let (png, width, height) = capture_primary_png()?;
    {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.pending_capture = Some(crate::bridge::PendingCapture { png, width, height });
    }
    show_overlay_window(app)?;
    let _ = app.emit("screenshot-preview-updated", ());
    Ok(())
}

fn ensure_remote_selection_ready(app: &AppHandle, state: &SharedBridge) -> Result<()> {
    let has_pending = state
        .lock()
        .expect("bridge state poisoned")
        .pending_capture
        .is_some();
    if has_pending {
        show_overlay_window(app)?;
    } else {
        prepare_remote_selection(app, state)?;
    }
    Ok(())
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

fn confirm_remote_selection(
    app: &AppHandle,
    state: &SharedBridge,
    payload: RemoteSelectionPayload,
) -> Result<()> {
    let pending = {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.pending_capture.take()
    }
    .ok_or_else(|| anyhow!("no pending screenshot"))?;

    let ratios = SelectionRatios {
        x_ratio: payload.x_ratio,
        y_ratio: payload.y_ratio,
        width_ratio: payload.width_ratio,
        height_ratio: payload.height_ratio,
    };
    let rect = ratios.to_rect(pending.width, pending.height);
    let (png, width, height) = crop_png(&pending.png, rect)?;
    let artifact = build_screenshot_message(png, width, height)?;
    let tx = state
        .lock()
        .expect("bridge state poisoned")
        .screenshot_tx
        .clone();
    let _ = tx.send(artifact.message);
    if let Some(window) = app.get_webview_window("overlay") {
        let _ = window.hide();
    }
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
pub fn open_screenshot_overlay(app: AppHandle) -> Result<(), String> {
    let bridge = app.state::<SharedBridge>();
    let (png, width, height) = capture_primary_png().map_err(|error| error.to_string())?;
    {
        let mut guard = bridge.lock().expect("bridge state poisoned");
        guard.pending_capture = Some(crate::bridge::PendingCapture { png, width, height });
    }

    show_overlay_window(&app).map_err(|error| error.to_string())?;
    let _ = app.emit("screenshot-preview-updated", ());
    Ok(())
}

fn show_overlay_window(app: &AppHandle) -> Result<()> {
    if let Some(window) = app.get_webview_window("overlay") {
        window.show()?;
        window.set_focus()?;
    } else {
        WebviewWindowBuilder::new(app, "overlay", WebviewUrl::App("overlay.html".into()))
            .title("选择截图区域")
            .transparent(true)
            .decorations(false)
            .always_on_top(true)
            .fullscreen(true)
            .skip_taskbar(true)
            .build()?;
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
    Ok(preview_from_png(pending.png, pending.width, pending.height))
}

#[tauri::command]
pub fn confirm_screenshot_selection(
    app: AppHandle,
    state: State<'_, SharedBridge>,
    rect: SelectionRect,
) -> Result<(), String> {
    let pending = {
        let mut guard = state.lock().expect("bridge state poisoned");
        guard.pending_capture.take()
    }
    .ok_or_else(|| "no pending screenshot".to_string())?;

    let (png, width, height) = crop_png(&pending.png, rect).map_err(|error| error.to_string())?;
    let artifact =
        build_screenshot_message(png, width, height).map_err(|error| error.to_string())?;
    let tx = state
        .lock()
        .expect("bridge state poisoned")
        .screenshot_tx
        .clone();
    let _ = tx.send(artifact.message);
    if let Some(window) = app.get_webview_window("overlay") {
        let _ = window.hide();
    }
    Ok(())
}
