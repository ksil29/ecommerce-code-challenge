import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../../api";
import { ADMIN } from "../../test/fixtures";
import { renderPage } from "../../test/render";
import { ImportPage } from "./ImportPage";

const csv = new File(["name,sku,description,category,price,stock,weight_kg\n"], "products.csv", { type: "text/csv" });

describe("ImportPage", () => {
  it("uploads the file and shows the report with the reason for each rejected row", async () => {
    const user = userEvent.setup();
    const importCsv = vi.spyOn(api.admin, "importCsv").mockResolvedValue({
      fileName: "products.csv",
      rowsRead: 4,
      blankRows: 0,
      created: 2,
      updated: 1,
      unchanged: 0,
      rejected: 1,
      conflicts: 0,
      duplicates: 0,
      warnings: 0,
      issues: [{ line: 4, sku: "WM-042", kind: "REJECTED", messages: ['Price "$29.99" is not a plain decimal such as 19.99'] }],
    });
    renderPage("/admin/import", <ImportPage />, { me: ADMIN });
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();

    await user.upload(screen.getByLabelText("CSV file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("Report for products.csv")).toBeInTheDocument();
    expect(importCsv).toHaveBeenCalledWith(csv);
    expect(screen.getByText("rejected")).toBeInTheDocument();
    expect(screen.getByText('Price "$29.99" is not a plain decimal such as 19.99')).toBeInTheDocument();
    expect(document.title).toBe("Import CSV · Admin · Shop");
  });

  it("marks a refused file and moves focus to it", async () => {
    const user = userEvent.setup();
    vi.spyOn(api.admin, "importCsv").mockRejectedValue(new ApiError(413, { detail: "Maximum upload size exceeded" }));
    renderPage("/admin/import", <ImportPage />, { me: ADMIN });

    await user.upload(screen.getByLabelText("CSV file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Maximum upload size exceeded");
    const file = screen.getByLabelText("CSV file");
    await waitFor(() => expect(file).toHaveFocus());
    expect(file).toHaveAttribute("aria-invalid", "true");
    expect(file).toHaveAccessibleDescription("Maximum upload size exceeded");
    expect(screen.queryByText(/Report for/)).not.toBeInTheDocument();
  });

  it("moves focus to the message when the server failed, not the file", async () => {
    const user = userEvent.setup();
    vi.spyOn(api.admin, "importCsv").mockRejectedValue(new ApiError(500, { detail: "Something went wrong on our side. Try again later." }));
    renderPage("/admin/import", <ImportPage />, { me: ADMIN });

    await user.upload(screen.getByLabelText("CSV file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));

    const alert = await screen.findByRole("alert");
    await waitFor(() => expect(alert).toHaveFocus());
    expect(screen.getByLabelText("CSV file")).not.toHaveAttribute("aria-invalid");
  });
});
