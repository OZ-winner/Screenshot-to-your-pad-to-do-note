use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct PairPayload {
    pub code: String,
    pub device_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CommandPayload {
    pub command: RemoteCommand,
    pub token: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct RemoteSelectionPayload {
    pub token: String,
    pub phase: RemoteSelectionPhase,
    pub x_ratio: f64,
    pub y_ratio: f64,
    pub width_ratio: f64,
    pub height_ratio: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RemoteSelectionPhase {
    Begin,
    Update,
    Confirm,
    Cancel,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RemoteCommand {
    Screenshot,
    PlayPause,
    #[serde(rename = "seek_back_5")]
    SeekBack5,
    #[serde(rename = "seek_forward_5")]
    SeekForward5,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMessage {
    Pair(PairPayload),
    Command(CommandPayload),
    RemoteSelection(RemoteSelectionPayload),
    Ping { token: Option<String> },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    PairingReady {
        server_name: String,
        code: String,
    },
    Paired {
        token: String,
        device_id: String,
    },
    Status {
        status: BridgeStatus,
        message: String,
    },
    Error {
        message: String,
    },
    Screenshot {
        id: String,
        filename: String,
        created_at: String,
        width: u32,
        height: u32,
        sha256: String,
        png_base64: String,
    },
    Pong,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BridgeStatus {
    Connected,
    Saving,
    Saved,
    Failed,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicDevice {
    pub id: String,
    pub name: String,
    pub last_seen: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppStatus {
    pub server_running: bool,
    pub port: u16,
    pub local_ip: String,
    pub pairing_code: String,
    pub pairing_url: String,
    pub connected_clients: usize,
    pub paired_devices: Vec<PublicDevice>,
}

#[cfg(test)]
mod tests {
    use super::{ClientMessage, RemoteCommand};

    #[test]
    fn parses_seek_commands_used_by_the_android_client() {
        let backward: ClientMessage = serde_json::from_str(
            r#"{"type":"command","command":"seek_back_5","token":"test"}"#,
        )
        .expect("backward command should parse");
        let forward: ClientMessage = serde_json::from_str(
            r#"{"type":"command","command":"seek_forward_5","token":"test"}"#,
        )
        .expect("forward command should parse");

        assert!(matches!(
            backward,
            ClientMessage::Command(command) if matches!(command.command, RemoteCommand::SeekBack5)
        ));
        assert!(matches!(
            forward,
            ClientMessage::Command(command) if matches!(command.command, RemoteCommand::SeekForward5)
        ));
    }
}
