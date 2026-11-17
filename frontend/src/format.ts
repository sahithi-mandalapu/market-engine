export function fmtPrice(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return "-";
  return n.toFixed(2);
}

export function fmtPct(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined || Number.isNaN(n)) return "-";
  const sign = n > 0 ? "+" : "";
  return `${sign}${n.toFixed(digits)}%`;
}

export function fmtMoney(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return "-";
  const abs = Math.abs(n).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (n < 0 ? "-$" : "$") + abs;
}

export function fmtTime(ms: number): string {
  const d = new Date(ms);
  return d.toISOString().slice(11, 19);  // HH:MM:SS in UTC, same clock as the exchange data
}

export function pnlClass(n: number): string {
  if (n > 0) return "up";
  if (n < 0) return "down";
  return "";
}
