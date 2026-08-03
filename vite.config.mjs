import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    strictPort: true,
    port: 1420,
    host: "0.0.0.0",
    watch: {
      ignored: [
        "**/src-tauri/target/**",
        "**/android/.gradle/**",
        "**/android/**/build/**",
      ],
    },
  },
  envPrefix: ["VITE_", "TAURI_"],
  build: {
    target: "es2020",
    minify: false,
    rollupOptions: {
      input: {
        main: resolve(process.cwd(), "index.html"),
        overlay: resolve(process.cwd(), "overlay.html"),
        selectionOutline: resolve(process.cwd(), "selection-outline.html"),
        captureToast: resolve(process.cwd(), "capture-toast.html"),
      },
    },
  },
});
