import { api } from "../api";
import { fmtPrice } from "../format";
import { usePoll } from "../useLiveFeed";

export function OrderBook({ symbol }: { symbol: string }) {
  const book = usePoll(() => (symbol ? api.book(symbol) : Promise.reject(new Error("no symbol"))), 1000, [symbol]);

  if (!symbol || !book) {
    return (
      <div className="panel">
        <h3>Order book</h3>
        <p className="muted">Waiting for the matching engine...</p>
      </div>
    );
  }

  const asks = [...book.asks].reverse();  // best ask at the bottom, next to the best bid
  const maxQty = Math.max(1, ...book.bids.map((l) => l.qty), ...book.asks.map((l) => l.qty));
  const spread = book.asks.length && book.bids.length ? book.asks[0].price - book.bids[0].price : null;

  const row = (l: { price: number; qty: number; orders: number }, side: "ask" | "bid") => (
    <tr key={`${side}${l.price}`} className={side}>
      <td className="num">{fmtPrice(l.price)}</td>
      <td className="num">{l.qty}</td>
      <td className="depth">
        <div className="bar" style={{ width: `${(l.qty / maxQty) * 100}%` }} />
      </td>
    </tr>
  );

  return (
    <div className="panel">
      <h3>
        Order book <span className="muted">{symbol}</span>
      </h3>
      <table className="table book">
        <thead>
          <tr>
            <th>Price</th>
            <th>Qty</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {asks.map((l) => row(l, "ask"))}
          <tr>
            <td colSpan={3} className="spread">
              spread {spread === null ? "-" : spread.toFixed(2)}
            </td>
          </tr>
          {book.bids.map((l) => row(l, "bid"))}
        </tbody>
      </table>
    </div>
  );
}
