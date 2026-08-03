import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import "./capture-toast.css";

type CaptureNotice = {
  revision: number;
  phase: "processing" | "success" | "failed";
  message: string;
};

const toast = document.getElementById("capture-toast")!;
const message = toast.querySelector<HTMLElement>(".message")!;

function render(notice: CaptureNotice) {
  toast.dataset.phase = notice.phase;
  message.textContent = notice.message;
}

async function initialize() {
  await listen<CaptureNotice>("capture-notice", (event) => render(event.payload));
  const status = await invoke<{ captureNotice: CaptureNotice | null }>("get_app_status");
  if (status.captureNotice) {
    render(status.captureNotice);
  }
}

initialize().catch(() => undefined);
