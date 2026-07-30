use anyhow::Result;
use enigo::{Direction, Enigo, Key, Keyboard, Settings};

use crate::protocol::RemoteCommand;

pub fn execute_media_command(command: RemoteCommand) -> Result<()> {
    let mut enigo = Enigo::new(&Settings::default())?;
    match command {
        RemoteCommand::PlayPause => enigo.key(Key::Space, Direction::Click)?,
        RemoteCommand::SeekBack5 => enigo.key(Key::LeftArrow, Direction::Click)?,
        RemoteCommand::SeekForward5 => enigo.key(Key::RightArrow, Direction::Click)?,
        RemoteCommand::Screenshot => {}
    }
    Ok(())
}
