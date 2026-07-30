import React, { useCallback, useEffect, useRef, useState } from "react";
import ReactDOM from "react-dom/client";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import "./overlay.css";

type Preview = {
  width: number;
  height: number;
  pngBase64: string;
};

type Point = {
  x: number;
  y: number;
};

type RemoteSelection = {
  active: boolean;
  xRatio: number;
  yRatio: number;
  widthRatio: number;
  heightRatio: number;
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
  const [remoteSelection, setRemoteSelection] = useState<RemoteSelection | null>(null);
  const imageRef = useRef<HTMLImageElement | null>(null);

  const rect = start && current ? normalize(start, current) : null;
  const activeRect = remoteSelection?.active
    ? {
        left: `${remoteSelection.xRatio * 100}%`,
        top: `${remoteSelection.yRatio * 100}%`,
        width: `${remoteSelection.widthRatio * 100}%`,
        height: `${remoteSelection.heightRatio * 100}%`,
      }
    : rect && preview
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
      const nextRemote = await invoke<RemoteSelection | null>("get_remote_selection");
      setPreview(nextPreview);
      setRemoteSelection(nextRemote?.active ? nextRemote : null);
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
    let unlistenRemote: undefined | (() => void);
    let unlistenPreview: undefined | (() => void);
    const poll = window.setInterval(() => {
      if (!preview) {
        loadPreview();
      }
      invoke<RemoteSelection | null>("get_remote_selection")
        .then((selection) => {
          setRemoteSelection(selection?.active ? selection : null);
        })
        .catch(() => undefined);
    }, 100);
    listen<RemoteSelection>("remote-selection", (event) => {
      setStart(null);
      setCurrent(null);
      setIsDragging(false);
      setLocalReady(false);
      setRemoteSelection(event.payload.active ? event.payload : null);
    }).then((dispose) => {
      unlistenRemote = dispose;
    });
    listen("screenshot-preview-updated", () => {
      setRemoteSelection(null);
      setStart(null);
      setCurrent(null);
      setIsDragging(false);
      setLocalReady(false);
      loadPreview();
    }).then((dispose) => {
      unlistenPreview = dispose;
    });
    return () => {
      window.clearInterval(poll);
      unlistenRemote?.();
      unlistenPreview?.();
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
        src={`data:image/png;base64,${preview.pngBase64}`}
        draggable={false}
        onPointerDown={(event) => {
          if (remoteSelection?.active) {
            return;
          }
          const point = pointFromEvent(event);
          setStart(point);
          setCurrent(point);
          setIsDragging(true);
          setLocalReady(false);
        }}
        onPointerMove={(event) => {
          if (start && isDragging && !remoteSelection?.active) {
            setCurrent(pointFromEvent(event));
          }
        }}
        onPointerUp={() => {
          if (rect && rect.width > 4 && rect.height > 4 && !remoteSelection?.active) {
            setLocalReady(true);
          }
          setIsDragging(false);
        }}
      />
      {activeRect && <div className="selection" style={activeRect} />}
      {rect && localReady && !remoteSelection?.active && (
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
      <div className="overlay-toolbar">
        {remoteSelection?.active ? "平板正在划定截图区域" : "拖拽选择区域"}
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("overlay-root")!).render(<Overlay />);
