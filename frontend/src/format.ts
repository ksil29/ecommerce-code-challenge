const currency = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });

export function money(amount: string): string {
  const value = Number(amount);
  return Number.isFinite(value) ? currency.format(value) : amount;
}

export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function uuid(): string {
  if (typeof crypto.randomUUID === "function" && window.isSecureContext) return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
