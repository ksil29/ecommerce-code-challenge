import { describe, expect, it, vi } from "vitest";
import { money, uuid } from "./format";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("format", () => {
  it("shows amounts in dollars and leaves anything else as it is", () => {
    expect(money("1234.5")).toBe("$1,234.50");
    expect(money("0")).toBe("$0.00");
    expect(money("n/a")).toBe("n/a");
  });

  it("uses the browser's UUID in a secure context", () => {
    vi.stubGlobal("isSecureContext", true);
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000000");

    expect(uuid()).toBe("00000000-0000-4000-8000-000000000000");
  });

  it("makes its own random version 4 UUID over plain HTTP", () => {
    vi.stubGlobal("isSecureContext", false);

    const keys = new Set(Array.from({ length: 100 }, () => uuid()));

    expect(keys.size).toBe(100);
    keys.forEach((key) => expect(key).toMatch(UUID_V4));
  });
});
