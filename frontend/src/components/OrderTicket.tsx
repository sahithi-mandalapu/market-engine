import { FormEvent, useState } from "react";
import { api } from "../api";
import { fmtPrice } from "../format";

type Props = { symbol: string; lastPrice: number | undefined };

export function OrderTicket({ symbol, lastPrice }: Props) {
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [type, setType] = useState<"MARKET" | "LIMIT">("MARKET");
  const [qty, setQty] = useState("10");
  const [price, setPrice] = useState("");
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const res = await api.placeOrder({
        symbol,
        side,
        type,
        qty: Number(qty),
        price: type === "LIMIT" ? Number(price) : 0,
      });
      const filled = res.fills.reduce((s, f) => s + f.qty, 0);
      if (res.status === "REJECTED") {
        setMsg({ text: `Rejected: ${res.reason}`, ok: false });
      } else {
        setMsg({ text: `#${res.orderId} ${res.status}, filled ${filled}, ${res.remaining} left`, ok: true });
      }
    } catch (err) {
      setMsg({ text: (err as Error).message, ok: false });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel">
      <h3>
        Order ticket <span className="muted">{symbol || "-"}</span>
      </h3>
      <form onSubmit={submit} className="ticket">
        <div className="seg">
          <button type="button" className={side === "BUY" ? "on buy" : ""} onClick={() => setSide("BUY")}>
            Buy
          </button>
          <button type="button" className={side === "SELL" ? "on sell" : ""} onClick={() => setSide("SELL")}>
            Sell
          </button>
        </div>
        <div className="seg">
          <button type="button" className={type === "MARKET" ? "on" : ""} onClick={() => setType("MARKET")}>
            Market
          </button>
          <button type="button" className={type === "LIMIT" ? "on" : ""} onClick={() => setType("LIMIT")}>
            Limit
          </button>
        </div>
        <label>
          Shares
          <input type="number" min={1} value={qty} onChange={(e) => setQty(e.target.value)} />
        </label>
        {type === "LIMIT" && (
          <label>
            Limit price
            <input
              type="number"
              step="0.01"
              min={0.01}
              value={price}
              placeholder={lastPrice ? fmtPrice(lastPrice) : ""}
              onChange={(e) => setPrice(e.target.value)}
            />
          </label>
        )}
        <button type="submit" disabled={busy || !symbol} className={`submit ${side === "BUY" ? "buy" : "sell"}`}>
          {side === "BUY" ? "Buy" : "Sell"} {symbol}
        </button>
        {msg && <p className={msg.ok ? "msg-ok" : "msg-err"}>{msg.text}</p>}
      </form>
    </div>
  );
}
