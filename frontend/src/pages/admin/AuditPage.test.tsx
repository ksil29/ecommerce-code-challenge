import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import { ADMIN } from "../../test/fixtures";
import { renderPage } from "../../test/render";
import { AuditPage } from "./AuditPage";

describe("AuditPage", () => {
  it("lays out JSON details, keeps plain text and shortens long ids", async () => {
    vi.spyOn(api.admin, "audit").mockResolvedValue([
      {
        id: 2,
        occurredAt: "2026-10-14T18:31:00Z",
        actor: "admin",
        action: "ORDER_PAID",
        entityType: "order",
        entityId: "0b6f3c1e-8a52-4d0e-9a3b-2f6c1d7e9a10",
        details: '{"total":"99.98"}',
      },
      { id: 1, occurredAt: "2026-10-14T18:30:00Z", actor: "admin", action: "PRODUCT_ARCHIVED", entityType: "product", entityId: "7", details: "not json" },
    ]);
    renderPage("/admin/audit", <AuditPage />, { me: ADMIN });

    expect(await screen.findByText(/"total": "99.98"/)).toBeInTheDocument();
    expect(screen.getByText("not json")).toBeInTheDocument();
    expect(screen.getByText("order:0b6f3c1e")).toBeInTheDocument();
    expect(screen.getByText("product:7")).toBeInTheDocument();
  });
});
