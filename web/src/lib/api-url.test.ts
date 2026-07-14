import { describe, expect, it } from "vitest";
import { alignConfiguredLoopbackHost } from "@/lib/api";

describe("development backend URL alignment", () => {
  it("keeps Strict cookies same-site when the frontend uses another loopback name", () => {
    expect(
      alignConfiguredLoopbackHost(
        "http://localhost:8080",
        "http://127.0.0.1:3000/files",
      ),
    ).toBe("http://127.0.0.1:8080");
    expect(
      alignConfiguredLoopbackHost(
        "ws://localhost:8080/ws",
        "http://127.0.0.1:3000/files",
      ),
    ).toBe("ws://127.0.0.1:8080/ws");
  });

  it("uses the page host for LAN development but preserves explicit remote backends", () => {
    expect(
      alignConfiguredLoopbackHost(
        "ws://localhost:8080/ws",
        "http://192.168.1.20:3000",
      ),
    ).toBe("ws://192.168.1.20:8080/ws");
    expect(
      alignConfiguredLoopbackHost(
        "wss://files.example.com/ws",
        "https://admin.example.com",
      ),
    ).toBe("wss://files.example.com/ws");
  });
});
