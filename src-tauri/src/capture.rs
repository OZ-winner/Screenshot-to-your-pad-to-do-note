use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose, Engine};
use chrono::Local;
use image::{DynamicImage, ImageOutputFormat};
use screenshots::Screen;
use sha2::{Digest, Sha256};
use std::io::Cursor;
use uuid::Uuid;

use crate::protocol::ServerMessage;

#[derive(Debug, Clone)]
pub struct ScreenshotArtifact {
    pub message: ServerMessage,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingPreview {
    pub width: u32,
    pub height: u32,
    pub png_base64: String,
}

#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectionRect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectionRatios {
    pub x_ratio: f64,
    pub y_ratio: f64,
    pub width_ratio: f64,
    pub height_ratio: f64,
}

impl SelectionRatios {
    pub fn to_rect(&self, screen_width: u32, screen_height: u32) -> SelectionRect {
        let x_ratio = self.x_ratio.clamp(0.0, 1.0);
        let y_ratio = self.y_ratio.clamp(0.0, 1.0);
        let width_ratio = self.width_ratio.clamp(0.0, 1.0 - x_ratio);
        let height_ratio = self.height_ratio.clamp(0.0, 1.0 - y_ratio);

        SelectionRect {
            x: (x_ratio * screen_width as f64).round() as u32,
            y: (y_ratio * screen_height as f64).round() as u32,
            width: (width_ratio * screen_width as f64).round().max(1.0) as u32,
            height: (height_ratio * screen_height as f64).round().max(1.0) as u32,
        }
    }
}

pub fn capture_primary_png() -> Result<(Vec<u8>, u32, u32)> {
    let screens = Screen::all().context("failed to enumerate screens")?;
    let screen = screens.first().ok_or_else(|| anyhow!("no screens found"))?;
    let image = screen
        .capture()
        .context("failed to capture primary screen")?;
    let width = image.width();
    let height = image.height();
    let mut cursor = Cursor::new(Vec::new());
    DynamicImage::ImageRgba8(image).write_to(&mut cursor, ImageOutputFormat::Png)?;
    Ok((cursor.into_inner(), width, height))
}

pub fn crop_png(png: &[u8], rect: SelectionRect) -> Result<(Vec<u8>, u32, u32)> {
    let image = image::load_from_memory(png)?.to_rgba8();
    let max_width = image.width();
    let max_height = image.height();

    let x = rect.x.min(max_width.saturating_sub(1));
    let y = rect.y.min(max_height.saturating_sub(1));
    let width = rect.width.min(max_width.saturating_sub(x)).max(1);
    let height = rect.height.min(max_height.saturating_sub(y)).max(1);

    let cropped = image::imageops::crop_imm(&image, x, y, width, height).to_image();
    let mut cursor = Cursor::new(Vec::new());
    DynamicImage::ImageRgba8(cropped).write_to(&mut cursor, ImageOutputFormat::Png)?;
    Ok((cursor.into_inner(), width, height))
}

pub fn build_screenshot_message(
    png: Vec<u8>,
    width: u32,
    height: u32,
) -> Result<ScreenshotArtifact> {
    let now = Local::now();
    let filename = format!("PC_{}.png", now.format("%Y%m%d_%H%M%S"));
    let mut hasher = Sha256::new();
    hasher.update(&png);
    let sha256 = format!("{:x}", hasher.finalize());

    Ok(ScreenshotArtifact {
        message: ServerMessage::Screenshot {
            id: Uuid::new_v4().to_string(),
            filename,
            created_at: now.to_rfc3339(),
            width,
            height,
            sha256,
            png_base64: general_purpose::STANDARD.encode(png),
        },
    })
}

pub fn preview_from_png(png: Vec<u8>, width: u32, height: u32) -> PendingPreview {
    PendingPreview {
        width,
        height,
        png_base64: general_purpose::STANDARD.encode(png),
    }
}
