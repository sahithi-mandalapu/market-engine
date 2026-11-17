import { api } from "../api";
import { fmtMoney, fmtPct, fmtPrice, pnlClass } from "../format";
import { usePoll } from "../useLiveFeed";

export function PortfolioPanel() {
  const p = usePoll(() => api.portfolio(), 1000);

  if (!p) {
    return (
      <div className="panel">
        <h3>Portfolio</h3>
        <p className="muted">Loading...</p>
      </div>
    );
  }

  return (
    <div className="panel">
      <h3>Portfolio</h3>
      <div className="stats">
        <div>
          <span className="muted">Equity</span>
          <b>{fmtMoney(p.equity)}</b>
        </div>
        <div>
          <span className="muted">Cash</span>
          <b>{fmtMoney(p.cash)}</b>
        </div>
        <div>
          <span className="muted">Total P&amp;L</span>
          <b className={pnlClass(p.totalPnl)}>{fmtMoney(p.totalPnl)}</b>
        </div>
        <div>
          <span className="muted">Realized</span>
          <b className={pnlClass(p.realizedPnl)}>{fmtMoney(p.realizedPnl)}</b>
        </div>
        <div>
          <span className="muted">Unrealized</span>
          <b className={pnlClass(p.unrealizedPnl)}>{fmtMoney(p.unrealizedPnl)}</b>
        </div>
      </div>

      {p.positions.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Symbol</th>
              <th>Qty</th>
              <th>Avg</th>
              <th>Mark</th>
              <th>P&amp;L</th>
            </tr>
          </thead>
          <tbody>
            {p.positions.map((pos) => (
              <tr key={pos.symbol}>
                <td>
                  <b>{pos.symbol}</b>
                </td>
                <td className="num">{pos.qty}</td>
                <td className="num">{fmtPrice(pos.avgCost)}</td>
                <td className="num">{fmtPrice(pos.mark)}</td>
                <td className={`num ${pnlClass(pos.unrealizedPnl)}`}>{fmtMoney(pos.unrealizedPnl)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h4>Allocation</h4>
      <div className="alloc">
        {Object.entries(p.allocation).map(([k, v]) => (
          <div key={k} className={`alloc-seg ${k === "CASH" ? "cash" : ""}`} style={{ flexGrow: Math.max(v, 0.001) }} title={`${k} ${fmtPct(v * 100, 1)}`}>
            {v > 0.08 ? k : ""}
          </div>
        ))}
      </div>

      <h4>Risk</h4>
      <p className="muted small">
        {p.risk.samples < 30
          ? `Only ${p.risk.samples} equity samples so far, numbers settle after ~30 seconds.`
          : "From the equity curve sampled once a second. Per-sample, not annualized."}
      </p>
      <div className="stats">
        <div>
          <span className="muted">Max drawdown</span>
          <b>{fmtPct(p.risk.maxDrawdown * 100)}</b>
        </div>
        <div>
          <span className="muted">VaR 95%</span>
          <b>{fmtPct(p.risk.var95 * 100, 3)}</b>
        </div>
        <div>
          <span className="muted">Exp. shortfall</span>
          <b>{fmtPct(p.risk.expectedShortfall95 * 100, 3)}</b>
        </div>
        <div>
          <span className="muted">Volatility</span>
          <b>{fmtPct(p.risk.volatilityPerSample * 100, 3)}</b>
        </div>
      </div>

      {p.openOrders.length > 0 && (
        <>
          <h4>Open orders</h4>
          <table className="table">
            <tbody>
              {p.openOrders.map((o) => (
                <tr key={o.id}>
                  <td>#{o.id}</td>
                  <td className={o.side === "BUY" ? "up" : "down"}>{o.side}</td>
                  <td>{o.symbol}</td>
                  <td className="num">
                    {o.remaining} @ {fmtPrice(o.price)}
                  </td>
                  <td>
                    <button className="small-btn" onClick={() => void api.cancelOrder(o.id)}>
                      cancel
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
