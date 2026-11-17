import { describe, expect, it } from "vitest";
import { describe as describeAlert, pricesTriggered, volumeTriggered } from "./alerts";
import { fmtMoney, fmtPct, fmtPrice, fmtTime, pnlClass } from "./format";
import type { Alert } from "./types";

describe("format", () => {
  it("formats prices and handles missing values", () => {
    expect(fmtPrice(190.5)).toBe("190.50");
    expect(fmtPrice(null)).toBe("-");
    expect(fmtPrice(undefined)).toBe("-");
    expect(fmtPrice(NaN)).toBe("-");
  });

  it("formats percentages with a sign", () => {
    expect(fmtPct(1.234)).toBe("+1.23%");
    expect(fmtPct(-0.5)).toBe("-0.50%");
    expect(fmtPct(0)).toBe("0.00%");
    expect(fmtPct(null)).toBe("-");
  });

  it("formats money", () => {
    expect(fmtMoney(1234.5)).toBe("$1,234.50");
    expect(fmtMoney(-99)).toBe("-$99.00");
    expect(fmtMoney(null)).toBe("-");
  });

  it("formats time as UTC clock", () => {
    expect(fmtTime(1_731_594_600_000)).toBe("14:30:00");
  });

  it("picks pnl colours", () => {
    expect(pnlClass(5)).toBe("up");
    expect(pnlClass(-5)).toBe("down");
    expect(pnlClass(0)).toBe("");
  });
});

describe("alerts", () => {
  const alerts: Alert[] = [
    { id: 1, symbol: "AAPL", kind: "price_above", value: 200, fired: false },
    { id: 2, symbol: "AAPL", kind: "price_below", value: 180, fired: false },
    { id: 3, symbol: "MSFT", kind: "price_above", value: 100, fired: false },
    { id: 4, symbol: "AAPL", kind: "volume_above", value: 5000, fired: false },
    { id: 5, symbol: "AAPL", kind: "price_above", value: 150, fired: true },
  ];

  it("fires price alerts only for the right symbol and direction", () => {
    expect(pricesTriggered(alerts, "AAPL", 201).map((a) => a.id)).toEqual([1]);
    expect(pricesTriggered(alerts, "AAPL", 179).map((a) => a.id)).toEqual([2]);
    expect(pricesTriggered(alerts, "AAPL", 190)).toEqual([]);
    expect(pricesTriggered(alerts, "MSFT", 101).map((a) => a.id)).toEqual([3]);
  });

  it("never fires an alert twice", () => {
    expect(pricesTriggered(alerts, "AAPL", 500).map((a) => a.id)).toEqual([1]);  // id 5 is already fired
  });

  it("uses strict comparison, equal to the threshold does not fire", () => {
    expect(pricesTriggered(alerts, "AAPL", 200)).toEqual([]);
  });

  it("fires volume alerts", () => {
    expect(volumeTriggered(alerts, "AAPL", 6000).map((a) => a.id)).toEqual([4]);
    expect(volumeTriggered(alerts, "AAPL", 4000)).toEqual([]);
    expect(volumeTriggered(alerts, "MSFT", 999999)).toEqual([]);
  });

  it("describes alerts", () => {
    expect(describeAlert(alerts[0])).toBe("AAPL price above 200");
    expect(describeAlert(alerts[3])).toBe("AAPL bar volume above 5000");
  });
});
