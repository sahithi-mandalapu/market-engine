import { useEffect, useRef, useState } from "react";
import { api } from "./api";
import type { AnomalyEvent, BarData, FillEvent } from "./types";

export type LiveState = {
  connected: boolean;
  prices: Record<string, number>;
  bars: Record<string, BarData[]>;
  anomalies: AnomalyEvent[];
  fills: FillEvent[];
  lastTick: { symbol: string; price: number; ts: number } | null;
  lastBar: BarData | null;
};

const MAX_BARS = 300;
const FLUSH_MS = 150;  // ticks can arrive hundreds of times a second, we only re-render a few times a second

/**
 * Opens the websocket and keeps a running picture of the market.
 * Reconnects with a short delay when the connection drops (the backend disconnects slow clients on purpose).
 */
export function useLiveFeed(): LiveState {
  const [state, setState] = useState<LiveState>({
    connected: false,
    prices: {},
    bars: {},
    anomalies: [],
    fills: [],
    lastTick: null,
    lastBar: null,
  });

  // mutable copy that the socket handler writes into, copied to React state on a timer
  const live = useRef<LiveState>({ ...state });
  const dirty = useRef(false);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const loadSnapshot = async () => {
      try {
        const rows = await api.symbols();
        for (const r of rows) {
          live.current.prices[r.symbol] = r.price;
          const bars = await api.bars(r.symbol, 150);
          live.current.bars[r.symbol] = bars;
        }
        const an = await api.anomalies();
        live.current.anomalies = an as unknown as AnomalyEvent[];
        dirty.current = true;
      } catch {
        // backend not up yet, the websocket will fill things in
      }
    };

    const connect = () => {
      const proto = window.location.protocol === "https:" ? "wss" : "ws";
      ws = new WebSocket(`${proto}://${window.location.host}/ws`);
      ws.onopen = () => {
        live.current.connected = true;
        dirty.current = true;
        void loadSnapshot();
      };
      ws.onclose = () => {
        live.current.connected = false;
        dirty.current = true;
        if (!closed) retry = setTimeout(connect, 1500);
      };
      ws.onmessage = (ev: MessageEvent<string>) => {
        const msg = JSON.parse(ev.data) as Record<string, unknown> & { type: string };
        const cur = live.current;
        if (msg.type === "tick") {
          const symbol = msg.symbol as string;
          const price = msg.price as number;
          cur.prices[symbol] = price;
          cur.lastTick = { symbol, price, ts: msg.ts as number };
        } else if (msg.type === "bar") {
          const bar = msg as unknown as BarData;
          const list = cur.bars[bar.symbol] ?? [];
          cur.bars[bar.symbol] = [...list, bar].slice(-MAX_BARS);
          cur.lastBar = bar;
        } else if (msg.type === "anomaly") {
          cur.anomalies = [msg as unknown as AnomalyEvent, ...cur.anomalies].slice(0, 60);
        } else if (msg.type === "fill") {
          cur.fills = [msg as unknown as FillEvent, ...cur.fills].slice(0, 40);
        }
        dirty.current = true;
      };
    };

    connect();
    const timer = setInterval(() => {
      if (!dirty.current) return;
      dirty.current = false;
      const c = live.current;
      // new object references so React notices the change
      setState({ ...c, prices: { ...c.prices }, bars: { ...c.bars } });
    }, FLUSH_MS);

    return () => {
      closed = true;
      clearInterval(timer);
      if (retry) clearTimeout(retry);
      ws?.close();
    };
  }, []);

  return state;
}

/** run `fn` every `ms` and keep its latest result. errors keep the previous value. */
export function usePoll<T>(fn: () => Promise<T>, ms: number, deps: unknown[] = []): T | null {
  const [value, setValue] = useState<T | null>(null);
  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const v = await fn();
        if (!stop) setValue(v);
      } catch {
        // keep the old value, we'll try again next time
      }
    };
    void tick();
    const id = setInterval(tick, ms);
    return () => {
      stop = true;
      clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, ...deps]);
  return value;
}
