import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("dashboard API client", () => {
  it.each([401, 403, 404])("preserves HTTP %s as a typed API error", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: "DENIED" }), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    ));

    const error = await api.session().catch((cause) => cause);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status, code: "DENIED" });
  });

  it("sends period and financial filters to the consolidated timeline endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await api.timeline("2026-09-01", "2026-09-30", {
      type: "EXPENSE",
      status: "OVERDUE",
      categoryId: "category-1",
      recurring: true,
    });

    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).toContain("from=2026-09-01");
    expect(url).toContain("to=2026-09-30");
    expect(url).toContain("type=EXPENSE");
    expect(url).toContain("status=OVERDUE");
    expect(url).toContain("categoryId=category-1");
    expect(url).toContain("recurring=true");
  });
});
