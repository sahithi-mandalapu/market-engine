import { fmtPct, fmtPrice, pnlClass } from "../format";
import type { BarData } from "../types";

type Props = {
  symbols: string[];
  prices: Record<string, number>;
  bars: Record<string, BarData[]>;
  selected: string;
  onSelect: (s: string) => void;
};

export function Watchlist({ symbols, prices, bars, selected, onSelect }: Props) {
  return (
    <div className="panel">
      <h3>Watchlist</h3>
      {symbols.length === 0 && <p className="muted">Nothing yet. Start a replay.</p>}
      <table className="table">
        <tbody>
          {symbols.map((s) => {
            const first = bars[s]?.[0]?.open;
            const price = prices[s];
            const change = first && price ? (price / first - 1) * 100 : 0;
            return (
              <tr key={s} className={s === selected ? "row-selected" : "row-click"} onClick={() => onSelect(s)}>
                <td>
                  <b>{s}</b>
                </td>
                <td className="num">{fmtPrice(price)}</td>
                <td className={`num ${pnlClass(change)}`}>{fmtPct(change)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
