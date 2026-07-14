import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@": new URL("./src", import.meta.url).pathname,
    },
  },
  test: {
    environment: "jsdom",
    env: {
      NEXT_PUBLIC_API_URL: "http://localhost:8080",
      NEXT_PUBLIC_WS_URL: "ws://localhost:8080/ws",
    },
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
