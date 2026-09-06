export type EntryType = "INCOME" | "EXPENSE";
export type Account = { id: string; name: string; type: string; active: boolean };
export type Category = { id: string; name: string; type: EntryType; expenseNature: string };
export type Catalog = { accounts: Account[]; categories: Category[] };
export type Transaction = { id: string; accountId: string; categoryId: string; type: EntryType; amount: string; description: string; occurredOn: string; source: string };
export type CashFlowEntry = { id: string; type: EntryType; amount: string; description: string; accountId: string | null; categoryId: string | null; recurrence: "ONCE" | "MONTHLY"; startDate: string; endDate: string | null; dayOfMonth: number | null; active: boolean };
export type Occurrence = { entryId: string; description: string; type: EntryType; plannedAmount: string; scheduledOn: string; status: "PENDING" | "PAID"; settlementId: string | null };
export type TimelineStatus = "PENDING" | "SETTLED" | "OVERDUE";
export type TimelineItem = { entryId: string; occurrenceId: string; settlementId: string | null; type: EntryType; description: string; categoryId: string | null; category: string | null; plannedAmount: string; settledAmount: string | null; dueDate: string; settledAt: string | null; status: TimelineStatus; source: string; recurring: boolean };
export type TimelineFilters = { type?: EntryType; status?: TimelineStatus; categoryId?: string; recurring?: boolean };
export type Settlement = { id: string; cashFlowEntryId: string; description: string; plannedAmount: string; settledAmount: string; variance: string; scheduledOn: string; paidOn: string; settledAt: string };
export type SettlementReceipt = { id: string; caption: string | null; contentType: string; sizeBytes: number; linkedAt: string; accessUrl: string };
export type Page<T> = { items: T[]; page: number; pageSize: number; total: number };
export type Overview = {
  from: string; to: string; currentBalance: string; projectedBalance: string;
  realizedIncome: string; realizedExpense: string; realizedResult: string; plannedIncome: string; plannedExpense: string;
  pendingIncome: string; pendingExpense: string; freeMoney: string; investedMoney: string; debt: string; netWorth: string; investedPercentage: string;
  accounts: { account: Account; balance: string }[];
  categories: { category: Category; realized: string; percentageOfExpenses: string }[];
  upcoming: Occurrence[];
};

export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code); }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/dashboard${path}`, {
    credentials: "include",
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: "REQUEST_FAILED" }));
    throw new ApiError(response.status, body.error ?? "REQUEST_FAILED");
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}

export const api = {
  session: () => request<{ name: string }>("/session"),
  login: (name: string, password: string) => request<{ name: string }>("/session", { method: "POST", body: JSON.stringify({ name, password }) }),
  logout: () => request<void>("/session", { method: "DELETE" }),
  overview: (from: string, to: string) => request<Overview>(`/overview?from=${from}&to=${to}`),
  catalog: () => request<Catalog>("/catalog"),
  createAccount: (name: string, type: string) => request<Account>("/accounts", { method: "POST", body: JSON.stringify({ name, type }) }),
  transactions: (from: string, to: string) => request<Page<Transaction>>(`/transactions?from=${from}&to=${to}&pageSize=100`),
  saveTransaction: (value: Omit<Transaction, "id" | "source">, id?: string) => request<Transaction>(`/transactions${id ? `/${id}` : ""}`, { method: id ? "PATCH" : "POST", body: JSON.stringify(value) }),
  deleteTransaction: (id: string) => request<Transaction>(`/transactions/${id}`, { method: "DELETE" }),
  cashFlow: (from: string, to: string) => request<Page<CashFlowEntry>>(`/cash-flow?from=${from}&to=${to}&pageSize=100`),
  saveCashFlow: (value: Omit<CashFlowEntry, "id">, id?: string) => request<CashFlowEntry>(`/cash-flow${id ? `/${id}` : ""}`, { method: id ? "PATCH" : "POST", body: JSON.stringify(value) }),
  deleteCashFlow: (id: string) => request<CashFlowEntry>(`/cash-flow/${id}`, { method: "DELETE" }),
  occurrences: (from: string, to: string) => request<Page<Occurrence>>(`/occurrences?from=${from}&to=${to}&pageSize=100`),
  timeline: (from: string, to: string, filters: TimelineFilters = {}) => {
    const params = new URLSearchParams({ from, to, pageSize: "100" });
    if (filters.type) params.set("type", filters.type);
    if (filters.status) params.set("status", filters.status);
    if (filters.categoryId) params.set("categoryId", filters.categoryId);
    if (filters.recurring !== undefined) params.set("recurring", String(filters.recurring));
    return request<Page<TimelineItem>>(`/timeline?${params}`);
  },
  settlements: (from: string, to: string) => request<Page<Settlement>>(`/settlements?from=${from}&to=${to}&pageSize=100`),
  receipts: (settlementId: string) => request<SettlementReceipt[]>(`/settlements/${settlementId}/receipts`),
};
