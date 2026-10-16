import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Pagination } from "./Pagination";
import { StockBadge } from "./StockBadge";
import { Thumb } from "./Thumb";

describe("Pagination", () => {
  it("is hidden when everything fits on one page", () => {
    const { container } = render(<Pagination page={0} totalPages={1} onChange={vi.fn()} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("disables the ends and asks for the neighbouring pages", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const { rerender } = render(<Pagination page={0} totalPages={3} onChange={onChange} />);
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(onChange).toHaveBeenLastCalledWith(1);

    rerender(<Pagination page={2} totalPages={3} onChange={onChange} />);

    expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Previous" }));
    expect(onChange).toHaveBeenLastCalledWith(1);
  });
});

describe("StockBadge", () => {
  it.each([
    [0, "Out of stock"],
    [9, "Only 9 left"],
    [10, "In stock"],
  ])("shows %i available as %s", (available, text) => {
    render(<StockBadge available={available} />);

    expect(screen.getByText(text)).toBeInTheDocument();
  });
});

describe("Thumb", () => {
  it("shows the initials of the first two words, or a question mark", () => {
    render(<Thumb name="  bluetooth   speaker mini" category="Audio" />);
    render(<Thumb name="!!!" category="Audio" />);

    expect(screen.getByText("BS")).toBeInTheDocument();
    expect(screen.getByText("?")).toBeInTheDocument();
  });
});
