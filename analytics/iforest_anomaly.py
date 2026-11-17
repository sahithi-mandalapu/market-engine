"""
Offline anomaly detection experiment: Isolation Forest vs the plain z-score rules the Java pipeline uses.

This is NOT wired into the Kafka pipeline. It reads the same tick CSV the replay uses, builds 1-minute bars,
and flags the weirdest ones with a model, so you can see what an ML detector adds on top of the statistics.

    python iforest_anomaly.py ../backend/src/main/resources/sample_session.csv --top 2
"""
import argparse
import csv
from collections import defaultdict

import numpy as np
from sklearn.ensemble import IsolationForest

BAR_MS = 60_000
ROLL = 30  # bars of history used for the rolling volume z-score


def read_ticks(path):
    ticks = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            ticks.append((int(row["ts"]), row["symbol"], float(row["price"]), int(row["size"]), int(row["seq"])))
    return ticks


def build_bars(ticks, bar_ms=BAR_MS):
    """ticks -> {symbol: [bar dict, ...]} sorted by time. Duplicate (symbol, seq) ticks are dropped."""
    seen = set()
    buckets = defaultdict(list)
    for ts, sym, price, size, seq in sorted(ticks, key=lambda t: (t[0], t[4])):
        if (sym, seq) in seen:
            continue
        seen.add((sym, seq))
        buckets[(sym, ts // bar_ms * bar_ms)].append((price, size))

    bars = defaultdict(list)
    for (sym, start), rows in sorted(buckets.items()):
        prices = [p for p, _ in rows]
        bars[sym].append({
            "start": start, "open": prices[0], "high": max(prices), "low": min(prices),
            "close": prices[-1], "volume": sum(s for _, s in rows),
        })
    return bars


def features(bars):
    """one row per bar (except the first): log return, high-low range, volume z-score vs the last ROLL bars"""
    n = len(bars)
    close = np.array([b["close"] for b in bars], dtype=float)
    vol = np.array([b["volume"] for b in bars], dtype=float)
    rng = np.array([(b["high"] - b["low"]) / b["open"] for b in bars], dtype=float)

    ret = np.zeros(n)
    ret[1:] = np.log(close[1:] / close[:-1])

    vol_z = np.zeros(n)
    for i in range(10, n):
        hist = vol[max(0, i - ROLL):i]
        vol_z[i] = (vol[i] - hist.mean()) / max(hist.std(ddof=1), 0.01 * hist.mean() + 1.0)

    X = np.column_stack([np.abs(ret), rng, vol_z])
    return X[1:], list(range(1, n))  # drop the first bar, it has no return


def zscore_flags(X, z=4.0):
    """the same idea as the Java rules: big |return| or big volume z-score compared to normal behaviour"""
    ret, vol_z = X[:, 0], X[:, 2]
    ret_z = (ret - np.median(ret)) / max(1.4826 * np.median(np.abs(ret - np.median(ret))), 1e-6)  # robust z
    return (np.abs(ret_z) >= z) | (vol_z >= z)


def iforest_scores(X, seed=0):
    model = IsolationForest(n_estimators=200, contamination="auto", random_state=seed)
    model.fit(X)
    return -model.score_samples(X)  # higher = weirder


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--top", type=float, default=2.0, help="flag this percent of bars as anomalous")
    args = ap.parse_args()

    bars = build_bars(read_ticks(args.csv))
    total = flagged_both = flagged_if = flagged_z = 0
    for sym, bs in sorted(bars.items()):
        X, idx = features(bs)
        if len(X) < 50:
            continue
        scores = iforest_scores(X)
        k = max(1, int(len(X) * args.top / 100))
        top = set(np.argsort(-scores)[:k].tolist())
        zf = set(np.nonzero(zscore_flags(X))[0].tolist())
        both = top & zf
        total += len(X)
        flagged_if += len(top)
        flagged_z += len(zf)
        flagged_both += len(both)
        print(f"{sym}: {len(X)} bars | iforest top {len(top)} | z-score rules {len(zf)} | overlap {len(both)}")
        for j in sorted(top)[:5]:
            b = bs[idx[j]]
            print(f"    bar {b['start']}  close {b['close']:.2f}  ret {X[j, 0] * 100:.2f}%  range {X[j, 1] * 100:.2f}%  vol_z {X[j, 2]:.1f}")
    print(f"\ntotal {total} bars | iforest flagged {flagged_if} | z-score flagged {flagged_z} | both {flagged_both}")


if __name__ == "__main__":
    main()
