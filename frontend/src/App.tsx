import { useEffect, useRef, useState } from "react";
import { describe, loadAlerts, pricesTriggered, saveAlerts, volumeTriggered } from "./alerts";
import { AlertsPanel } from "./components/AlertsPanel";
import { Chart } from "./components/Chart";
import { AnomalyFeed, FillFeed } from "./components/Feeds";
import { OrderBook } from "./components/OrderBook";
import { OrderTicket } from "./components/OrderTicket";
import { PortfolioPanel } from "./components/PortfolioPanel";
import { ReplayControls } from "./components/ReplayControls";
import { Watchlist } from "./components/Watchlist";
import type { Alert } from "./types";
import { useLiveFeed } from "./useLiveFeed";

export default function App() {
  const live = useLiveFeed();
  const symbols = Object.keys(live.prices).sort();
  const [picked, setPicked] = useState("");
  const selected = picked && symbols.includes(picked) ? picked : symbols[0] ?? "";

  const [alerts, setAlerts] = useState<Alert[]>(() => loadAlerts());
  const [alertLog, setAlertLog] = useState<string[]>([]);
  const nextId = useRef(Math.max(0, ...alerts.map((a) => a.id)) + 1);

  useEffect(() => saveAlerts(alerts), [alerts]);

  // check price alerts whenever prices change
  useEffect(() => {
    const fired: Alert[] = [];
    for (const sym of Object.keys(live.prices)) fired.push(...pricesTriggered(alerts, sym, live.prices[sym]));
    markFired(fired);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live.prices]);

  // and volume alerts when a bar closes
  useEffect(() => {
    if (!live.lastBar) return;
    markFired(volumeTriggered(alerts, live.lastBar.symbol, live.lastBar.volume));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live.lastBar]);

  function markFired(fired: Alert[]) {
    if (fired.length === 0) return;
    const ids = new Set(fired.map((a) => a.id));
    setAlerts((prev) => prev.map((a) => (ids.has(a.id) ? { ...a, fired: true } : a)));
    setAlertLog((prev) => [...fired.map((a) => describe(a)), ...prev].slice(0, 20));
  }

  const addAlert = (a: Omit<Alert, "id" | "fired">) =>
    setAlerts((prev) => [...prev, { ...a, id: nextId.current++, fired: false } as Alert]);

  return (
    <div className="app">
      <header>
        <h1>Market Engine</h1>
        <span className={live.connected ? "dot on" : "dot"} title={live.connected ? "live" : "disconnected"} />
        <ReplayControls />
      </header>

      <main>
        <aside>
          <Watchlist symbols={symbols} prices={live.prices} bars={live.bars} selected={selected} onSelect={setPicked} />
          <AlertsPanel
            symbols={symbols}
            alerts={alerts}
            log={alertLog}
            onAdd={addAlert}
            onRemove={(id) => setAlerts((p) => p.filter((a) => a.id !== id))}
          />
        </aside>

        <section className="center">
          <Chart symbol={selected} bars={live.bars[selected] ?? []} price={live.prices[selected]} />
          <div className="row">
            <OrderBook symbol={selected} />
            <OrderTicket symbol={selected} lastPrice={live.prices[selected]} />
          </div>
        </section>

        <aside>
          <PortfolioPanel />
          <AnomalyFeed anomalies={live.anomalies} />
          <FillFeed fills={live.fills} />
        </aside>
      </main>
    </div>
  );
}
