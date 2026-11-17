import { FormEvent, useState } from "react";
import { describe } from "../alerts";
import type { Alert } from "../types";

type Props = {
  symbols: string[];
  alerts: Alert[];
  log: string[];
  onAdd: (a: Omit<Alert, "id" | "fired">) => void;
  onRemove: (id: number) => void;
};

export function AlertsPanel({ symbols, alerts, log, onAdd, onRemove }: Props) {
  const [symbol, setSymbol] = useState("");
  const [kind, setKind] = useState<Alert["kind"]>("price_above");
  const [value, setValue] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const sym = symbol || symbols[0];
    const v = Number(value);
    if (!sym || !Number.isFinite(v) || v <= 0) return;
    onAdd({ symbol: sym, kind, value: v });
    setValue("");
  };

  return (
    <div className="panel">
      <h3>Alerts</h3>
      <form className="alert-form" onSubmit={submit}>
        <select value={symbol || symbols[0] || ""} onChange={(e) => setSymbol(e.target.value)}>
          {symbols.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
        <select value={kind} onChange={(e) => setKind(e.target.value as Alert["kind"])}>
          <option value="price_above">price above</option>
          <option value="price_below">price below</option>
          <option value="volume_above">bar volume above</option>
        </select>
        <input type="number" step="0.01" placeholder="value" value={value} onChange={(e) => setValue(e.target.value)} />
        <button type="submit">Add</button>
      </form>
      <ul className="feed">
        {alerts.map((a) => (
          <li key={a.id} className={a.fired ? "fired" : ""}>
            {describe(a)} {a.fired && <span className="muted">(fired)</span>}
            <button className="small-btn" onClick={() => onRemove(a.id)}>
              x
            </button>
          </li>
        ))}
      </ul>
      {log.length > 0 && (
        <>
          <h4>Triggered</h4>
          <ul className="feed">
            {log.slice(0, 5).map((l, i) => (
              <li key={i} className="up">
                {l}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
