import { useState } from "react";
import { fmtPrice } from "../format";
import type { BarData } from "../types";

type Props = { symbol: string; bars: BarData[]; price: number | undefined };

const W = 780;
const H = 300;
const RSI_H = 70;
const PAD_R = 54;
const SHOWN = 90;

/** line through the points, skipping gaps where the indicator isn't warmed up yet (null) */
function linePath(values: (number | null)[], x: (i: number) => number, y: (v: number) => number): string {
  let d = "";
  let pen = false;
  values.forEach((v, i) => {
    if (v === null) {
      pen = false;
      return;
    }
    d += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)} `;
    pen = true;
  });
  return d;
}

export function Chart({ symbol, bars, price }: Props) {
  const [showBands, setShowBands] = useState(true);
  const [showSma, setShowSma] = useState(true);

  const data = bars.slice(-SHOWN);
  if (data.length < 2) {
    return (
      <div className="panel chart-empty">
        <h3>{symbol || "Chart"}</h3>
        <p className="muted">No bars yet. Start a replay to stream some market data.</p>
      </div>
    );
  }

  const plotW = W - PAD_R;
  const step = plotW / data.length;
  const x = (i: number) => i * step + step / 2;

  let lo = Math.min(...data.map((b) => b.low));
  let hi = Math.max(...data.map((b) => b.high));
  if (showBands) {
    for (const b of data) {
      if (b.indicators.bbLower !== null) lo = Math.min(lo, b.indicators.bbLower);
      if (b.indicators.bbUpper !== null) hi = Math.max(hi, b.indicators.bbUpper);
    }
  }
  const margin = (hi - lo) * 0.05 || 1;
  lo -= margin;
  hi += margin;
  const y = (v: number) => H - ((v - lo) / (hi - lo)) * H;
  const yRsi = (v: number) => RSI_H - (v / 100) * RSI_H;

  const gridValues = [0, 0.25, 0.5, 0.75, 1].map((f) => lo + (hi - lo) * f);
  const anomalyIdx = data.map((b, i) => (b.anomalies.length > 0 ? i : -1)).filter((i) => i >= 0);
  const last = data[data.length - 1];
  const livePrice = price ?? last.close;

  return (
    <div className="panel">
      <div className="chart-head">
        <h3>
          {symbol} <span className="muted">1m bars</span>
        </h3>
        <label>
          <input type="checkbox" checked={showSma} onChange={(e) => setShowSma(e.target.checked)} /> SMA20
        </label>
        <label>
          <input type="checkbox" checked={showBands} onChange={(e) => setShowBands(e.target.checked)} /> Bollinger
        </label>
        <span className="price-big">{fmtPrice(livePrice)}</span>
      </div>

      <svg viewBox={`0 0 ${W} ${H + RSI_H + 24}`} className="chart" role="img" aria-label={`${symbol} candlestick chart`}>
        {gridValues.map((g) => (
          <g key={g}>
            <line x1={0} x2={plotW} y1={y(g)} y2={y(g)} className="grid" />
            <text x={plotW + 4} y={y(g) + 4} className="axis">
              {g.toFixed(2)}
            </text>
          </g>
        ))}

        {showBands && (
          <>
            <path d={linePath(data.map((b) => b.indicators.bbUpper), x, y)} className="line band" />
            <path d={linePath(data.map((b) => b.indicators.bbLower), x, y)} className="line band" />
          </>
        )}
        {showSma && <path d={linePath(data.map((b) => b.indicators.sma20), x, y)} className="line sma" />}

        {data.map((b, i) => {
          const up = b.close >= b.open;
          const top = y(Math.max(b.open, b.close));
          const h = Math.max(1, Math.abs(y(b.open) - y(b.close)));
          return (
            <g key={b.start} className={up ? "candle up" : "candle down"}>
              <line x1={x(i)} x2={x(i)} y1={y(b.high)} y2={y(b.low)} />
              <rect x={x(i) - step * 0.35} y={top} width={step * 0.7} height={h} />
            </g>
          );
        })}

        {anomalyIdx.map((i) => (
          <polygon
            key={`a${i}`}
            className="anomaly-mark"
            points={`${x(i)},${y(data[i].high) - 12} ${x(i) - 5},${y(data[i].high) - 3} ${x(i) + 5},${y(data[i].high) - 3}`}
          >
            <title>{data[i].anomalies.map((a) => a.message).join("\n")}</title>
          </polygon>
        ))}

        <line x1={0} x2={plotW} y1={y(livePrice)} y2={y(livePrice)} className="live-line" />

        {/* RSI panel */}
        <g transform={`translate(0, ${H + 24})`}>
          <rect x={0} y={0} width={plotW} height={RSI_H} className="rsi-bg" />
          <line x1={0} x2={plotW} y1={yRsi(70)} y2={yRsi(70)} className="grid" />
          <line x1={0} x2={plotW} y1={yRsi(30)} y2={yRsi(30)} className="grid" />
          <path d={linePath(data.map((b) => b.indicators.rsi14), x, yRsi)} className="line rsi" />
          <text x={plotW + 4} y={yRsi(70) + 4} className="axis">70</text>
          <text x={plotW + 4} y={yRsi(30) + 4} className="axis">30</text>
          <text x={4} y={12} className="axis">RSI 14</text>
        </g>
      </svg>
    </div>
  );
}
