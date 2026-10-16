import { useState, type FormEvent } from "react";
import { api, ApiError, errorMessage } from "../../api";
import type { ImportReport, IssueKind } from "../../types";
import { useErrorFocus } from "../../useErrorFocus";
import { usePageTitle } from "../../usePageTitle";

const KIND_BADGE: Record<IssueKind, string> = {
  REJECTED: "text-bg-danger",
  CONFLICT: "text-bg-warning",
  DUPLICATE: "text-bg-secondary",
  WARNING: "text-bg-info",
};

export function ImportPage() {
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<ImportReport>();
  const [error, setError] = useState<{ text: string; fileRefused: boolean }>();
  const [busy, setBusy] = useState(false);
  const { formRef, messageRef, failed } = useErrorFocus();
  usePageTitle("Import CSV · Admin");

  const upload = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!file) return;
    setBusy(true);
    setError(undefined);
    try {
      setReport(await api.admin.importCsv(file));
    } catch (failure) {
      setReport(undefined);
      setError({
        text: errorMessage(failure),
        fileRefused: failure instanceof ApiError && (failure.status === 400 || failure.status === 413),
      });
      failed();
    } finally {
      setBusy(false);
    }
  };

  const totals: [string, number][] = report
    ? [
        ["Rows read", report.rowsRead],
        ["Created", report.created],
        ["Updated", report.updated],
        ["Unchanged", report.unchanged],
        ["Rejected", report.rejected],
        ["Conflicts", report.conflicts],
        ["Duplicates", report.duplicates],
        ["Warnings", report.warnings],
        ["Blank rows", report.blankRows],
      ]
    : [];

  return (
    <>
      <h1 className="h4" tabIndex={-1}>
        Import products from CSV
      </h1>
      <p className="text-body-secondary small">
        Columns: name, sku, description, category, price, stock, weight_kg. SKU is the key: a new SKU is created, a known one
        is updated, and when a SKU repeats in the file the last row wins. Rows that fail validation are rejected with a reason
        and the rest still go in. The whole file is applied in one transaction.
      </p>
      <form ref={formRef} className="d-flex flex-wrap gap-2 mb-4" onSubmit={(event) => void upload(event)}>
        <input
          type="file"
          accept=".csv,text/csv"
          className={`form-control w-auto ${error?.fileRefused ? "is-invalid" : ""}`}
          aria-label="CSV file"
          aria-invalid={error?.fileRefused || undefined}
          aria-describedby={error?.fileRefused ? "import-error" : undefined}
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
        />
        <button type="submit" className="btn btn-primary" disabled={!file || busy}>
          {busy ? "Importing…" : "Import"}
        </button>
      </form>
      {error && (
        <div id="import-error" ref={messageRef} tabIndex={-1} className="alert alert-danger" role="alert">
          {error.text}
        </div>
      )}
      {report && (
        <>
          <h2 className="h5">Report for {report.fileName}</h2>
          <div className="row row-cols-3 row-cols-md-5 row-cols-lg-9 g-2 mb-4">
            {totals.map(([label, value]) => (
              <div className="col" key={label}>
                <div className="border rounded p-2 text-center h-100">
                  <div className="fs-5 fw-semibold">{value}</div>
                  <div className="small text-body-secondary">{label}</div>
                </div>
              </div>
            ))}
          </div>
          {report.issues.length > 0 && (
            <div className="table-responsive">
              <table className="table table-sm align-middle">
                <thead>
                  <tr>
                    <th>Line</th>
                    <th>SKU</th>
                    <th>Result</th>
                    <th>Details</th>
                  </tr>
                </thead>
                <tbody>
                  {report.issues.map((issue, index) => (
                    <tr key={`${issue.line}-${issue.kind}-${index}`}>
                      <td>{issue.line}</td>
                      <td className="mono">{issue.sku ?? "—"}</td>
                      <td>
                        <span className={`badge ${KIND_BADGE[issue.kind]}`}>{issue.kind.toLowerCase()}</span>
                      </td>
                      <td>
                        <ul className="issue-list small">
                          {issue.messages.map((text) => (
                            <li key={text}>{text}</li>
                          ))}
                        </ul>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </>
  );
}
