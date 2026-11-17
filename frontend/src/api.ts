import type {
  AnomalyEvent,
  BarData,
  BookData,
  OrderResponse,
  PortfolioData,
  ReplayStatus,
  SymbolRow,
} from "./types";

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    // empty or non-json body
  }
  if (!res.ok) {
    const msg = (body as { error?: string } | null)?.error ?? `${res.status} ${res.statusText}`;
    throw new Error(msg);
  }
  return body as T;
}

const json = (data: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(data),
});

export const api = {
  symbols: () => request<SymbolRow[]>("/api/symbols"),
  bars: (symbol: string, limit = 120) => request<BarData[]>(`/api/bars?symbol=${symbol}&limit=${limit}`),
  anomalies: () => request<AnomalyEvent[]>("/api/anomalies?limit=40"),
  book: (symbol: string) => request<BookData>(`/api/book?symbol=${symbol}&depth=8`),
  portfolio: () => request<PortfolioData>("/api/portfolio"),
  replay: () => request<ReplayStatus>("/api/replay"),
  startReplay: (source: string, speed: number) => request<ReplayStatus>("/api/replay/start", json({ source, speed })),
  stopReplay: () => request<ReplayStatus>("/api/replay/stop", { method: "POST" }),
  placeOrder: (o: { symbol: string; side: string; type: string; qty: number; price: number }) =>
    request<OrderResponse>("/api/orders", json(o)),
  cancelOrder: (id: number) => request<unknown>(`/api/orders/${id}`, { method: "DELETE" }),
};
