mod bridge;
mod capture;
mod commands;
mod media;
mod protocol;

use bridge::{BridgeState, SharedBridge};
use commands::{
    clear_devices, confirm_screenshot_selection, get_app_status, get_pending_preview,
    get_remote_selection, open_screenshot_overlay, regenerate_pairing_code, send_media_command,
    start_bridge_server,
};
use std::sync::{Arc, Mutex};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Manager,
};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .manage(Arc::new(Mutex::new(BridgeState::default())))
        .setup(|app| {
            {
                let bridge = app.state::<SharedBridge>().inner().clone();
                if let Ok(mut state) = bridge.lock() {
                    let _ = bridge::load_devices(app.handle(), &mut state);
                };
            }
            if let Err(error) = setup_tray(app) {
                eprintln!("tray setup failed: {error}");
            }

            if let Err(error) = setup_shortcut(app) {
                eprintln!("global shortcut setup failed: {error}");
            }

            if let Err(error) = commands::precreate_overlay_window(app.handle()) {
                eprintln!("overlay precreate failed: {error}");
            }

            let app_handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let bridge = app_handle.state::<SharedBridge>().inner().clone();
                let _ = commands::spawn_bridge_server(app_handle, bridge, None).await;
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_app_status,
            start_bridge_server,
            regenerate_pairing_code,
            clear_devices,
            send_media_command,
            open_screenshot_overlay,
            get_pending_preview,
            get_remote_selection,
            confirm_screenshot_selection
        ])
        .run(tauri::generate_context!())
        .expect("failed to run tablet shot bridge");
}

fn setup_tray(app: &mut tauri::App) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "显示", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;
    let _tray = TrayIconBuilder::new()
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle();
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
        })
        .build(app)?;
    Ok(())
}

fn setup_shortcut(app: &mut tauri::App) -> Result<(), String> {
    let shortcut = Shortcut::new(Some(Modifiers::CONTROL | Modifiers::ALT), Code::KeyA);
    let app_handle = app.handle().clone();
    app.global_shortcut()
        .on_shortcut(shortcut, move |_app, _shortcut, event| {
            if event.state == ShortcutState::Pressed {
                let app_handle = app_handle.clone();
                tauri::async_runtime::spawn(async move {
                    let _ = open_screenshot_overlay(app_handle).await;
                });
            }
        })
        .map_err(|error| error.to_string())?;
    Ok(())
}
