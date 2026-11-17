import { useState } from "react";
import { api } from "../api";
import { usePoll } from "../useLiveFeed";

export function ReplayControls() {
  const [source, setSource] = useState("sample");
  const [speed, setSpeed] = useState("60");
  const [error, setError] = useState("");
  const status = usePoll(() => api.replay(), 1000);

  const running = status?.state === "running";
  const pct = status && status.total > 0 ? Math.round((status.played / status.total) * 100) : 0;

  const start = async () => {
    setError("");
    try {
      await api.startReplay(source, Number(speed));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="replay">
      <select value={source} onChange={(e) => setSource(e.target.value)} disabled={running}>
        <option value="sample">sample day</option>
        <option value="synthetic">synthetic (messy feed)</option>
      </select>
      <select value={speed} onChange={(e) => setSpeed(e.target.value)} disabled={running}>
        <option value="1">1x</option>
        <option value="10">10x</option>
        <option value="60">60x</option>
        <option value="300">300x</option>
        <option value="0">max</option>
      </select>
      {running ? (
        <button onClick={() => void api.stopReplay()}>Stop</button>
      ) : (
        <button className="primary" onClick={() => void start()}>
          Start replay
        </button>
      )}
      {status && status.state !== "idle" && (
        <span className="muted">
          {status.state} {pct}%
        </span>
      )}
      {error && <span className="msg-err">{error}</span>}
    </div>
  );
}
