import { uuid } from "./format";

const STORAGE_KEY = "shop.checkout";

export interface Attempt {
  key: string;
  signature: string;
}

export function purchaseSignature(items: { productId: number; quantity: number }[], total: string): string {
  return JSON.stringify({ items, total });
}

function stored(): Attempt | null {
  try {
    const parsed: unknown = JSON.parse(window.sessionStorage.getItem(STORAGE_KEY) ?? "null");
    if (typeof parsed !== "object" || parsed === null) return null;
    const { key, signature } = parsed as Partial<Attempt>;
    return typeof key === "string" && typeof signature === "string" ? { key, signature } : null;
  } catch {
    return null;
  }
}

function store(attempt: Attempt | null) {
  try {
    if (attempt) {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt));
    } else {
      window.sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    return;
  }
}

export function resumeAttempt(signature: string, current: Attempt | null): Attempt {
  if (current?.signature === signature) return current;
  const saved = stored();
  if (saved?.signature === signature) return saved;
  const attempt = { key: uuid(), signature };
  store(attempt);
  return attempt;
}

export function forgetAttempt() {
  store(null);
}
