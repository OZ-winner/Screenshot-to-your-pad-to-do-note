import React, { useEffect, useMemo, useState } from "react";
import ReactDOM from "react-dom/client";
import QRCode from "qrcode";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { Camera, Eraser, FastForward, MonitorSmartphone, Pause, Play, RefreshCw, Rewind, ShieldCheck, Wifi } from "lucide-react";
import "./styles.css";

type Device = {
  id: string;
  name: string;
  lastSeen: string;
};

type AppStatus = {
  serverRunning: boolean;
  port: number;
  localIp: string;
  pairingCode: string;
  pairingUrl: string;
  connectedClients: number;
  pairedDevices: Device[];
  captureNotice: CaptureNotice | null;
};

type CaptureNotice = {
  revision: number;
  phase: "processing" | "success" | "failed";
  message: string;
};

const defaultStatus: AppStatus = {
  serverRunning: false,
  port: 46731,
  localIp: "127.0.0.1",
  pairingCode: "",
  pairingUrl: "",
  connectedClients: 0,
  pairedDevices: [],
  captureNotice: null,
};

function App() {
  const [status, setStatus] = useState<AppStatus>(defaultStatus);
  const [qr, setQr] = useState("");
  const [message, setMessage] = useState("正在启动局域网服务...");
  const [captureNotice, setCaptureNotice] = useState<CaptureNotice | null>(null);

  const pairingPayload = useMemo(() => {
    return JSON.stringify({
      name: "截图直传",
      url: status.pairingUrl,
      code: status.pairingCode,
    });
  }, [status.pairingUrl, status.pairingCode]);

  async function refreshStatus() {
    const next = await invoke<AppStatus>("get_app_status");
    setStatus(next);
    setCaptureNotice(next.captureNotice);
    setMessage(next.serverRunning ? "服务已启动，平板扫码后即可连接。" : "服务尚未启动。");
  }

  async function startServer() {
    const next = await invoke<AppStatus>("start_bridge_server", { port: status.port });
    setStatus(next);
  }

  async function regenerateCode() {
    const next = await invoke<AppStatus>("regenerate_pairing_code");
    setStatus(next);
  }

  async function clearDevices() {
    const next = await invoke<AppStatus>("clear_devices");
    setStatus(next);
  }

  async function remote(command: "play_pause" | "seek_back_5" | "seek_forward_5") {
    await invoke("send_media_command", { command });
  }

  async function screenshot() {
    await invoke("open_screenshot_overlay");
  }

  useEffect(() => {
    refreshStatus().catch((error) => setMessage(String(error)));
    const timer = window.setInterval(() => {
      refreshStatus().catch(() => undefined);
    }, 2500);
    let unlisten: undefined | (() => void);
    listen("bridge-status", () => refreshStatus().catch(() => undefined)).then((dispose) => {
      unlisten = dispose;
    });
    let unlistenCapture: undefined | (() => void);
    let unlistenCaptureCleared: undefined | (() => void);
    listen<CaptureNotice>("capture-notice", (event) => {
      setCaptureNotice(event.payload);
    }).then((dispose) => {
      unlistenCapture = dispose;
    });
    listen<number>("capture-notice-cleared", (event) => {
      setCaptureNotice((current) => current?.revision === event.payload ? null : current);
    }).then((dispose) => {
      unlistenCaptureCleared = dispose;
    });
    return () => {
      window.clearInterval(timer);
      unlisten?.();
      unlistenCapture?.();
      unlistenCaptureCleared?.();
    };
  }, []);

  useEffect(() => {
    if (!status.pairingUrl) {
      setQr("");
      return;
    }
    QRCode.toDataURL(pairingPayload, { margin: 1, width: 220 }).then(setQr).catch(() => setQr(""));
  }, [pairingPayload, status.pairingUrl]);

  return (
    <main className="app-shell">
      <section className="topbar">
        <div>
          <h1>截图直传</h1>
          <p data-capture-phase={captureNotice?.phase}>{captureNotice?.message || message}</p>
        </div>
        <div className="status-pill" data-state={status.serverRunning ? "on" : "off"}>
          <Wifi size={18} />
          {status.serverRunning ? "局域网在线" : "未启动"}
        </div>
      </section>

      <section className="content-grid">
        <div className="panel pairing-panel">
          <div className="panel-heading">
            <div>
              <h2>平板配对</h2>
              <p>小米/安卓平板打开接收 App 后扫码连接。</p>
            </div>
            <button className="icon-button" title="刷新配对码" onClick={regenerateCode}>
              <RefreshCw size={18} />
            </button>
          </div>

          <div className="pairing-body">
            <div className="qr-box">{qr ? <img src={qr} alt="配对二维码" /> : <span>等待服务</span>}</div>
            <div className="pairing-details">
              <label>配对码</label>
              <strong>{status.pairingCode || "------"}</strong>
              <label>电脑地址</label>
              <code>{status.pairingUrl || "服务未启动"}</code>
              <label>连接数</label>
              <span>{status.connectedClients}</span>
            </div>
          </div>

          <div className="button-row">
            <button onClick={startServer}>
              <Wifi size={17} />
              启动服务
            </button>
            <button onClick={screenshot}>
              <Camera size={17} />
              选区截图
            </button>
          </div>
        </div>

        <div className="panel">
          <div className="panel-heading">
            <div>
              <h2>视频遥控测试</h2>
              <p>控制当前活动窗口，默认后退/快进 5 秒。</p>
            </div>
            <MonitorSmartphone size={24} />
          </div>
          <div className="remote-grid">
            <button onClick={() => remote("seek_back_5")} title="后退 5 秒">
              <Rewind size={22} />
              后退
            </button>
            <button onClick={() => remote("play_pause")} title="暂停/播放">
              <Pause size={22} />
              暂停
            </button>
            <button onClick={() => remote("seek_forward_5")} title="快进 5 秒">
              <FastForward size={22} />
              快进
            </button>
          </div>
          <div className="hint">
            <Play size={16} />
            把焦点留在浏览器或播放器上，再从平板悬浮窗发送命令。
          </div>
        </div>

        <div className="panel devices-panel">
          <div className="panel-heading">
            <div>
              <h2>已配对平板</h2>
              <p>只有这些设备能接收截图和发送遥控命令。</p>
            </div>
            <button className="icon-button danger" title="清空配对设备" onClick={clearDevices}>
              <Eraser size={18} />
            </button>
          </div>
          {status.pairedDevices.length === 0 ? (
            <div className="empty-state">
              <ShieldCheck size={28} />
              <span>还没有配对设备</span>
            </div>
          ) : (
            <ul className="device-list">
              {status.pairedDevices.map((device) => (
                <li key={device.id}>
                  <span>{device.name}</span>
                  <time>{new Date(device.lastSeen).toLocaleString()}</time>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </main>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(<App />);
