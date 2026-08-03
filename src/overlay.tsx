import React, { useCallback, useEffect, useRef, useState } from "react";
import ReactDOM from "react-dom/client";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import "./overlay.css";

type Preview = {
  width: number;
  height: number;
  mimeType: string;
  imageBase64: string;
};

type Point = {
  x: number;
  y: number;
};

function normalize(start: Point, end: Point) {
  return {
    x: Math.min(start.x, end.x),
    y: Math.min(start.y, end.y),
    width: Math.abs(start.x - end.x),
    height: Math.abs(start.y - end.y),
  };
}

function Overlay() {
  const [preview, setPreview] = useState<Preview | null>(null);
  const [start, setStart] = useState<Point | null>(null);
  const [current, setCurrent] = useState<Point | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [localReady, setLocalReady] = useState(false);
  const imageRef = useRef<HTMLImageElement | null>(null);

  const rect = start && current ? normalize(start, current) : null;
  const activeRect = rect && preview
    ? {
        left: `${(rect.x / preview.width) * 100}%`,
        top: `${(rect.y / preview.height) * 100}%`,
        width: `${(rect.width / preview.width) * 100}%`,
        height: `${(rect.height / preview.height) * 100}%`,
      }
    : null;

  const loadPreview = useCallback(async () => {
    try {
      const nextPreview = await invoke<Preview>("get_pending_preview");
      setPreview(nextPreview);
    } catch {
      setPreview(null);
    }
  }, []);

  useEffect(() => {
    loadPreview();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        getCurrentWindow().hide();
      }
      if (event.key === "Enter" && rect && localReady) {
        confirm();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [loadPreview, rect]);

  useEffect(() => {
    let unlistenPreview: undefined | (() => void);
    let unlistenPreviewReset: undefined | (() => void);
    const poll = window.setInterval(() => {
      if (!preview) {
        loadPreview();
      }
    }, 100);
    listen("screenshot-preview-updated", () => {
      loadPreview();
    }).then((dispose) => {
      unlistenPreview = dispose;
    });
    listen("screenshot-preview-reset", () => {
      setPreview(null);
      setStart(null);
      setCurrent(null);
      setIsDragging(false);
      setLocalReady(false);
    }).then((dispose) => {
      unlistenPreviewReset = dispose;
    });
    return () => {
      window.clearInterval(poll);
      unlistenPreview?.();
      unlistenPreviewReset?.();
    };
  }, [loadPreview, preview]);

  function pointFromEvent(event: React.PointerEvent): Point {
    const bounds = imageRef.current!.getBoundingClientRect();
    const scaleX = preview!.width / bounds.width;
    const scaleY = preview!.height / bounds.height;
    return {
      x: Math.round((event.clientX - bounds.left) * scaleX),
      y: Math.round((event.clientY - bounds.top) * scaleY),
    };
  }

  async function confirm() {
    if (!rect || rect.width < 4 || rect.height < 4) {
      return;
    }
    await invoke("confirm_screenshot_selection", { rect });
  }

  if (!preview) {
    return <div className="overlay-loading">正在准备截图...</div>;
  }

  return (
    <div className="overlay-shell">
      <img
        ref={imageRef}
        className="screen-preview"
        src={`data:${preview.mimeType};base64,${preview.imageBase64}`}
        draggable={false}
        onPointerDown={(event) => {
          const point = pointFromEvent(event);
          setStart(point);
          setCurrent(point);
          setIsDragging(true);
          setLocalReady(false);
        }}
        onPointerMove={(event) => {
          if (start && isDragging) {
            setCurrent(pointFromEvent(event));
          }
        }}
        onPointerUp={() => {
          if (rect && rect.width > 4 && rect.height > 4) {
            setLocalReady(true);
          }
          setIsDragging(false);
        }}
      />
      {activeRect && <div className="selection" style={activeRect} />}
      {rect && localReady && (
        <div
          className="selection-actions"
          style={{
            left: `${Math.min(92, (rect.x / preview.width) * 100 + (rect.width / preview.width) * 100)}%`,
            top: `${Math.min(92, (rect.y / preview.height) * 100 + (rect.height / preview.height) * 100)}%`,
          }}
        >
          <button title="取消" onClick={() => getCurrentWindow().hide()}>
            ×
          </button>
          <button title="重新划区" onClick={() => {
            setStart(null);
            setCurrent(null);
            setLocalReady(false);
          }}>
            ↺
          </button>
          <button title="保存" onClick={confirm}>
            ✓
          </button>
        </div>
      )}
      <div className="overlay-toolbar">拖拽选择区域</div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("overlay-root")!).render(<Overlay />);
