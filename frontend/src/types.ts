export type Indicators = {
  sma20: number | null;
  sma50: number | null;
  ema12: number | null;
  ema26: number | null;
  rsi14: number | null;
  macd: number | null;
  macdSignal: number | null;
  macdHist: number | null;
  bbMid: number | null;
  bbUpper: number | null;
  bbLower: number | null;
};

export type AnomalyInfo = {
  type: string;
  symbol: string;
  barStart: number;
  score: number;
  message: string;
};

export type BarData = {
  symbol: string;
  start: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  ticks: number;
  indicators: Indicators;
  anomalies: AnomalyInfo[];
};

export type SymbolRow = { symbol: string; price: number; changePct: number };

export type AnomalyEvent = {
  anomalyType: string;
  symbol: string;
  barStart: number;
  score: number;
  message: string;
};

export type FillEvent = {
  ts: number;
  orderId: number;
  symbol: string;
  side: "BUY" | "SELL";
  price: number;
  qty: number;
};

export type Level = { price: number; qty: number; orders: number };
export type BookData = { bids: Level[]; asks: Level[] };

export type Position = {
  symbol: string;
  qty: number;
  avgCost: number;
  mark: number;
  marketValue: number;
  unrealizedPnl: number;
};

export type OpenOrder = {
  id: number;
  symbol: string;
  side: "BUY" | "SELL";
  type: "LIMIT" | "MARKET";
  price: number;
  remaining: number;
};

export type PortfolioData = {
  startingCash: number;
  cash: number;
  equity: number;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  positions: Position[];
  allocation: Record<string, number>;
  openOrders: OpenOrder[];
  risk: {
    samples: number;
    volatilityPerSample: number;
    maxDrawdown: number;
    var95: number;
    expectedShortfall95: number;
  };
};

export type ReplayStatus = { state: string; source: string; speed: number; total: number; played: number };

export type OrderResponse = {
  orderId: number;
  status: string;
  reason: string;
  remaining: number;
  resting: boolean;
  fills: { price: number; qty: number }[];
};

export type Alert =
  | { id: number; symbol: string; kind: "price_above" | "price_below"; value: number; fired: boolean }
  | { id: number; symbol: string; kind: "volume_above"; value: number; fired: boolean };
