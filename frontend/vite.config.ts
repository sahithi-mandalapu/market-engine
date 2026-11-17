import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// in dev the backend runs on :8080, proxy so the browser only talks to one origin
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
  test: { environment: "node" },
});
