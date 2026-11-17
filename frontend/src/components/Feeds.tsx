import { fmtPrice, fmtTime } from "../format";
import type { AnomalyEvent, FillEvent } from "../types";

export function AnomalyFeed({ anomalies }: { anomalies: AnomalyEvent[] }) {
  return (
    <div className="panel">
      <h3>Anomalies</h3>
      {anomalies.length === 0 && <p className="muted">Nothing unusual yet.</p>}
      <ul className="feed">
        {anomalies.slice(0, 12).map((a, i) => (
          <li key={`${a.symbol}${a.barStart}${a.anomalyType}${i}`}>
            <span className={`tag tag-${a.anomalyType}`}>{a.anomalyType.replace("_", " ")}</span>
            <span className="muted"> {fmtTime(a.barStart)}</span>
            <div>{a.message}</div>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function FillFeed({ fills }: { fills: FillEvent[] }) {
  if (fills.length === 0) return null;
  return (
    <div className="panel">
      <h3>Recent fills</h3>
      <ul className="feed">
        {fills.slice(0, 8).map((f, i) => (
          <li key={`${f.orderId}-${i}`}>
            <span className={f.side === "BUY" ? "up" : "down"}>{f.side}</span> {f.qty} {f.symbol} @ {fmtPrice(f.price)}
          </li>
        ))}
      </ul>
    </div>
  );
}
