function paletteIndex(text: string): number {
  let hash = 0;
  for (const char of text) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return hash % 8;
}

function initials(name: string): string {
  const letters = name
    .split(/\s+/)
    .map((word) => word.replace(/[^\p{L}\p{N}]/gu, ""))
    .filter((word) => word.length > 0)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "");
  return letters.join("") || "?";
}

export function Thumb({ name, category, large = false }: { name: string; category: string; large?: boolean }) {
  return (
    <div className={`thumb thumb-${paletteIndex(category)} ${large ? "thumb-large" : ""}`} aria-hidden="true">
      {initials(name)}
    </div>
  );
}
