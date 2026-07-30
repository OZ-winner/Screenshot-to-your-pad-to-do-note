import React, { useEffect, useRef, useState } from "react";
import ReactDOM from "react-dom/client";
import { invoke } from "@tauri-apps/api/core";
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
  const imageRef = useRef<HTMLImageElement | null>(null);

  const rect = start && current ? normalize(start, current) : null;

  useEffect(() => {
    invoke<Preview>("get_pending_preview").then(setPreview).catch(() => getCurrentWindow().hide());
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        getCurrentWindow().hide();
      }
      if (event.key === "Enter" && rect) {
        confirm();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [rect]);

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
          const point = pointFromEvent(event);
          setStart(point);
          setCurrent(point);
        }}
        onPointerMove={(event) => {
          if (start) {
            setCurrent(pointFromEvent(event));
          }
        }}
        onPointerUp={() => {
          if (rect && rect.width > 4 && rect.height > 4) {
            confirm();
          }
        }}
      />
      {rect && (
        <div
          className="selection"
          style={{
            left: `${(rect.x / preview.width) * 100}%`,
            top: `${(rect.y / preview.height) * 100}%`,
            width: `${(rect.width / preview.width) * 100}%`,
            height: `${(rect.height / preview.height) * 100}%`,
          }}
        />
      )}
      <div className="overlay-toolbar">拖拽选择区域，松开后发送到平板；Esc 取消</div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("overlay-root")!).render(<Overlay />);
