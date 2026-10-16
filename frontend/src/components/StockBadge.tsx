export function StockBadge({ available }: { available: number }) {
  if (available <= 0) return <span className="badge text-bg-secondary">Out of stock</span>;
  if (available < 10) return <span className="badge text-bg-warning">Only {available} left</span>;
  return <span className="badge text-bg-success">In stock</span>;
}
