use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose, Engine};
use chrono::Local;
use image::{
    codecs::{
        jpeg::JpegEncoder,
        png::{CompressionType, FilterType, PngEncoder},
    },
    ColorType, ImageEncoder, RgbaImage,
};
use screenshots::Screen;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::protocol::ServerMessage;

#[derive(Debug, Clone)]
pub struct ScreenshotArtifact {
    pub id: String,
    pub message: ServerMessage,
}

#[derive(Debug, Clone)]
pub struct CapturedScreen {
    pub pixels: RgbaImage,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingPreview {
    pub width: u32,
    pub height: u32,
    pub mime_type: String,
    pub image_base64: String,
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
        let screen_width = screen_width.max(1);
        let screen_height = screen_height.max(1);
        let x_ratio = self.x_ratio.clamp(0.0, 1.0);
        let y_ratio = self.y_ratio.clamp(0.0, 1.0);
        let width_ratio = self.width_ratio.clamp(0.0, 1.0 - x_ratio);
        let height_ratio = self.height_ratio.clamp(0.0, 1.0 - y_ratio);
        let x =
            ((x_ratio * screen_width as f64).round() as u32).min(screen_width.saturating_sub(1));
        let y =
            ((y_ratio * screen_height as f64).round() as u32).min(screen_height.saturating_sub(1));
        let width = ((width_ratio * screen_width as f64).round() as u32)
            .max(1)
            .min(screen_width - x);
        let height = ((height_ratio * screen_height as f64).round() as u32)
            .max(1)
            .min(screen_height - y);

        SelectionRect {
            x,
            y,
            width,
            height,
        }
    }
}

pub fn capture_primary_png() -> Result<(Vec<u8>, u32, u32)> {
    let capture = capture_primary_raw()?;
    let png = encode_rgba_png_fast(&capture.pixels)?;
    Ok((png, capture.width, capture.height))
}

pub fn capture_primary_raw() -> Result<CapturedScreen> {
    let screens = Screen::all().context("failed to enumerate screens")?;
    let screen = screens.first().ok_or_else(|| anyhow!("no screens found"))?;
    let image = screen
        .capture()
        .context("failed to capture primary screen")?;
    let width = image.width();
    let height = image.height();
    Ok(CapturedScreen {
        pixels: image,
        width,
        height,
    })
}

pub fn crop_rgba(image: &RgbaImage, rect: SelectionRect) -> RgbaImage {
    let max_width = image.width();
    let max_height = image.height();

    let x = rect.x.min(max_width.saturating_sub(1));
    let y = rect.y.min(max_height.saturating_sub(1));
    let width = rect.width.min(max_width.saturating_sub(x)).max(1);
    let height = rect.height.min(max_height.saturating_sub(y)).max(1);

    image::imageops::crop_imm(image, x, y, width, height).to_image()
}

pub fn crop_rgba_to_png(image: &RgbaImage, rect: SelectionRect) -> Result<(Vec<u8>, u32, u32)> {
    let cropped = crop_rgba(image, rect);
    let width = cropped.width();
    let height = cropped.height();
    let png = encode_rgba_png_fast(&cropped)?;
    Ok((png, width, height))
}

pub fn encode_rgba_png_fast(image: &RgbaImage) -> Result<Vec<u8>> {
    let mut png = Vec::new();
    PngEncoder::new_with_quality(&mut png, CompressionType::Fast, FilterType::NoFilter)
        .write_image(
            image.as_raw(),
            image.width(),
            image.height(),
            ColorType::Rgba8,
        )?;
    Ok(png)
}

pub fn encode_rgba_jpeg(image: &RgbaImage, quality: u8) -> Result<Vec<u8>> {
    let rgb = rgba_to_rgb(image);
    let mut jpeg = Vec::new();
    JpegEncoder::new_with_quality(&mut jpeg, quality).encode(
        &rgb,
        image.width(),
        image.height(),
        ColorType::Rgb8,
    )?;
    Ok(jpeg)
}

fn rgba_to_rgb(image: &RgbaImage) -> Vec<u8> {
    let mut rgb = Vec::with_capacity((image.width() * image.height() * 3) as usize);
    for pixel in image.pixels() {
        rgb.extend_from_slice(&pixel.0[..3]);
    }
    rgb
}

pub fn preview_from_rgba(image: &RgbaImage) -> Result<PendingPreview> {
    let jpeg = encode_rgba_jpeg(image, 85)?;
    Ok(PendingPreview {
        width: image.width(),
        height: image.height(),
        mime_type: "image/jpeg".to_string(),
        image_base64: general_purpose::STANDARD.encode(jpeg),
    })
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

    let id = Uuid::new_v4().to_string();
    Ok(ScreenshotArtifact {
        id: id.clone(),
        message: ServerMessage::Screenshot {
            id,
            filename,
            created_at: now.to_rfc3339(),
            width,
            height,
            sha256,
            png_base64: general_purpose::STANDARD.encode(png),
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_image() -> RgbaImage {
        image::ImageBuffer::from_fn(4, 3, |x, y| {
            image::Rgba([(x * 40) as u8, (y * 50) as u8, 7, 255])
        })
    }

    #[test]
    fn crops_raw_pixels_inside_bounds() {
        let image = test_image();
        let cropped = crop_rgba(
            &image,
            SelectionRect {
                x: 1,
                y: 1,
                width: 2,
                height: 2,
            },
        );

        assert_eq!((cropped.width(), cropped.height()), (2, 2));
        assert_eq!(cropped.get_pixel(0, 0).0, [40, 50, 7, 255]);
        assert_eq!(cropped.get_pixel(1, 1).0, [80, 100, 7, 255]);
    }

    #[test]
    fn crop_clamps_boundary_coordinates() {
        let image = test_image();
        let cropped = crop_rgba(
            &image,
            SelectionRect {
                x: 99,
                y: 99,
                width: 99,
                height: 99,
            },
        );

        assert_eq!((cropped.width(), cropped.height()), (1, 1));
        assert_eq!(cropped.get_pixel(0, 0).0, [120, 100, 7, 255]);
    }

    #[test]
    fn jpeg_preview_is_decodable() {
        let image = test_image();
        let jpeg = encode_rgba_jpeg(&image, 85).expect("jpeg encodes");
        let decoded = image::load_from_memory(&jpeg).expect("jpeg decodes");

        assert_eq!((decoded.width(), decoded.height()), (4, 3));
    }

    #[test]
    fn final_png_keeps_dimensions_and_pixels() {
        let image = test_image();
        let (png, width, height) = crop_rgba_to_png(
            &image,
            SelectionRect {
                x: 0,
                y: 0,
                width: 4,
                height: 3,
            },
        )
        .expect("png encodes");
        let decoded = image::load_from_memory(&png)
            .expect("png decodes")
            .to_rgba8();

        assert_eq!((width, height), (4, 3));
        assert_eq!((decoded.width(), decoded.height()), (4, 3));
        assert_eq!(decoded.get_pixel(2, 1).0, [80, 50, 7, 255]);
    }

    #[test]
    fn selection_ratios_map_to_primary_screen_pixels() {
        let rect = SelectionRatios {
            x_ratio: 0.25,
            y_ratio: 0.1,
            width_ratio: 0.5,
            height_ratio: 0.75,
        }
        .to_rect(1920, 1080);

        assert_eq!((rect.x, rect.y), (480, 108));
        assert_eq!((rect.width, rect.height), (960, 810));
    }

    #[test]
    fn selection_ratios_clamp_to_screen_boundaries() {
        let rect = SelectionRatios {
            x_ratio: 0.9,
            y_ratio: -0.5,
            width_ratio: 0.5,
            height_ratio: 2.0,
        }
        .to_rect(1000, 500);

        assert_eq!((rect.x, rect.y), (900, 0));
        assert_eq!((rect.width, rect.height), (100, 500));
    }

    #[test]
    fn selection_at_bottom_right_stays_inside_the_screen() {
        let rect = SelectionRatios {
            x_ratio: 1.0,
            y_ratio: 1.0,
            width_ratio: 0.0,
            height_ratio: 0.0,
        }
        .to_rect(1000, 500);

        assert_eq!((rect.x, rect.y), (999, 499));
        assert_eq!((rect.width, rect.height), (1, 1));
    }
}
