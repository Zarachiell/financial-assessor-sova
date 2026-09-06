import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, Catalog, TimelineItem } from "@/lib/api";
import { brl, DashboardSkeleton, ReceiptLinks, TimelineView } from "./page";

const catalog: Catalog = {
  accounts: [],
  categories: [{ id: "category-1", name: "Moradia", type: "EXPENSE", expenseNature: "FIXED" }],
};

const timelineItem: TimelineItem = {
  entryId: "entry-1",
  occurrenceId: "entry-1:2026-09-10",
  settlementId: "settlement-1",
  type: "EXPENSE",
  description: "Aluguel",
  categoryId: "category-1",
  category: "Moradia",
  plannedAmount: "850.00",
  settledAmount: "875.00",
  dueDate: "2026-09-10",
  settledAt: "2026-09-09T12:00:00Z",
  status: "SETTLED",
  source: "WHATSAPP_TEXT",
  recurring: true,
};

afterEach(cleanup);

describe("dashboard financial presentation", () => {
  it("renders a loading state and formats BRL without losing cents", () => {
    render(<DashboardSkeleton />);
    expect(screen.getByLabelText("Carregando dashboard")).toBeInTheDocument();
    expect(brl("1234.56")).toMatch(/1\.234,56/);
  });

  it("renders an empty timeline", () => {
    render(<TimelineView rows={[]} catalog={catalog} filters={{}} onFilters={vi.fn()} />);
    expect(screen.getByText("Nenhuma ocorrência corresponde aos filtros.")).toBeInTheDocument();
  });

  it("distinguishes planned, realized and settled values", () => {
    render(<TimelineView rows={[timelineItem]} catalog={catalog} filters={{}} onFilters={vi.fn()} />);
    expect(screen.getByText("Previsto")).toBeInTheDocument();
    expect(screen.getByText("Realizado")).toBeInTheDocument();
    expect(screen.getAllByText("Liquidado")).toHaveLength(2);
    expect(screen.getByText(/850,00/)).toBeInTheDocument();
    expect(screen.getByText(/875,00/)).toBeInTheDocument();
  });

  it("sends selected status back to the backend filter state", () => {
    const onFilters = vi.fn();
    render(<TimelineView rows={[]} catalog={catalog} filters={{}} onFilters={onFilters} />);
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "OVERDUE" } });
    expect(onFilters).toHaveBeenCalledWith({ status: "OVERDUE" });
  });

  it("renders multiple authorized receipt links and tolerates missing receipts", async () => {
    vi.spyOn(api, "receipts")
      .mockResolvedValueOnce([
        { id: "r1", caption: "PIX", contentType: "image/jpeg", sizeBytes: 10, linkedAt: "2026-09-01", accessUrl: "/signed/1" },
        { id: "r2", caption: null, contentType: "application/pdf", sizeBytes: 20, linkedAt: "2026-09-01", accessUrl: "/signed/2" },
      ])
      .mockResolvedValueOnce([]);
    const { unmount } = render(<ReceiptLinks settlementId="s1" />);
    fireEvent.click(screen.getByRole("button", { name: "Comprovantes" }));
    expect(await screen.findByRole("link", { name: "PIX" })).toHaveAttribute("href", "/signed/1");
    expect(screen.getByRole("link", { name: "Comprovante 2" })).toBeInTheDocument();
    unmount();
    render(<ReceiptLinks settlementId="s2" />);
    fireEvent.click(screen.getByRole("button", { name: "Comprovantes" }));
    await waitFor(() => expect(screen.getByText("Sem comprovante")).toBeInTheDocument());
  });

  it("isolates a receipt loading error from the rest of the screen", async () => {
    vi.spyOn(api, "receipts").mockRejectedValue(new Error("offline"));
    render(<ReceiptLinks settlementId="s1" />);
    fireEvent.click(screen.getByRole("button", { name: "Comprovantes" }));
    expect(await screen.findByText("Falha ao carregar")).toBeInTheDocument();
  });
});
