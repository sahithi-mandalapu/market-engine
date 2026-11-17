import type { Alert } from "./types";

const KEY = "market-engine-alerts";

export function loadAlerts(): Alert[] {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Alert[]) : [];
  } catch {
    return [];
  }
}

export function saveAlerts(alerts: Alert[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(alerts));
  } catch {
    // private mode or full storage, alerts just won't survive a reload
  }
}

export function describe(a: Alert): string {
  switch (a.kind) {
    case "price_above":
      return `${a.symbol} price above ${a.value}`;
    case "price_below":
      return `${a.symbol} price below ${a.value}`;
    case "volume_above":
      return `${a.symbol} bar volume above ${a.value}`;
  }
}

/** alerts that fire for this price. each alert only fires once (fired flag), the caller marks them */
export function pricesTriggered(alerts: Alert[], symbol: string, price: number): Alert[] {
  return alerts.filter(
    (a) =>
      !a.fired &&
      a.symbol === symbol &&
      ((a.kind === "price_above" && price > a.value) || (a.kind === "price_below" && price < a.value)),
  );
}

export function volumeTriggered(alerts: Alert[], symbol: string, volume: number): Alert[] {
  return alerts.filter((a) => !a.fired && a.symbol === symbol && a.kind === "volume_above" && volume > a.value);
}
