import { api } from "../../api";
import { dateTime } from "../../format";
import { useLoad } from "../../useLoad";
import { usePageTitle } from "../../usePageTitle";

function pretty(details: string): string {
  try {
    return JSON.stringify(JSON.parse(details), null, 1);
  } catch {
    return details;
  }
}

export function AuditPage() {
  const events = useLoad("audit", () => api.admin.audit());
  usePageTitle("Audit log · Admin");

  return (
    <>
      <div className="d-flex align-items-center mb-3">
        <h1 className="h4 me-auto mb-0" tabIndex={-1}>
          Audit log
        </h1>
        <button type="button" className="btn btn-outline-secondary btn-sm" onClick={events.reload}>
          Refresh
        </button>
      </div>
      <p className="small text-body-secondary">
        Every change to products, every import, account event and order status change, written in the same transaction as
        the change itself.
      </p>
      {events.error && (
        <div className="alert alert-danger" role="alert">
          {events.error}
        </div>
      )}
      {events.data && (
        <div className="surface table-responsive">
          <table className="table table-sm align-middle mb-0">
            <thead>
              <tr>
                <th>When</th>
                <th>Who</th>
                <th>Action</th>
                <th>Entity</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {events.data.map((event) => (
                <tr key={event.id}>
                  <td className="small text-nowrap">{dateTime(event.occurredAt)}</td>
                  <td className="small">{event.actor}</td>
                  <td>
                    <span className="badge text-bg-light border">{event.action}</span>
                  </td>
                  <td className="small mono">
                    {event.entityType}
                    {event.entityId && <>:{event.entityId.length > 12 ? event.entityId.slice(0, 8) : event.entityId}</>}
                  </td>
                  <td>
                    <pre className="details-json mb-0">{pretty(event.details)}</pre>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
