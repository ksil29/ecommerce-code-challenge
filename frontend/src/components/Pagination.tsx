interface Props {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}

export function Pagination({ page, totalPages, onChange }: Props) {
  if (totalPages <= 1) return null;
  return (
    <nav className="d-flex align-items-center gap-3 mt-4" aria-label="Pages">
      <button type="button" className="btn btn-outline-secondary btn-sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span className="text-body-secondary">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        className="btn btn-outline-secondary btn-sm"
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
      >
        Next
      </button>
    </nav>
  );
}
