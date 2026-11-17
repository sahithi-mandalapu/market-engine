import numpy as np

from iforest_anomaly import build_bars, features, iforest_scores, zscore_flags


def make_ticks(n_bars=300, seed=0, jump_at=None, spike_at=None):
    """random walk, 6 ticks per minute. optionally a +4% jump or a 25x volume spike at a given bar"""
    rng = np.random.default_rng(seed)
    price, seq, ticks = 100.0, 0, []
    for b in range(n_bars):
        for k in range(6):
            price *= np.exp(0.0003 * rng.standard_normal())
            if jump_at is not None and b == jump_at and k == 0:
                price *= 1.04
            size = int(50 + rng.integers(0, 40))
            if spike_at is not None and b == spike_at:
                size *= 25
            seq += 1
            ticks.append((b * 60_000 + k * 9_000, "TEST", round(price, 2), size, seq))
    return ticks


def test_bars_are_built_and_duplicates_dropped():
    ticks = make_ticks(n_bars=5)
    bars = build_bars(ticks + ticks[:10])  # first 10 ticks sent twice
    assert len(bars["TEST"]) == 5
    assert sum(b["volume"] for b in bars["TEST"]) == sum(t[3] for t in ticks)


def test_features_shape():
    bars = build_bars(make_ticks(n_bars=60))["TEST"]
    X, idx = features(bars)
    assert X.shape == (59, 3)
    assert idx[0] == 1


def test_isolation_forest_ranks_injected_events_at_the_top():
    bars = build_bars(make_ticks(n_bars=300, jump_at=200, spike_at=250))["TEST"]
    X, idx = features(bars)
    scores = iforest_scores(X)
    top5 = {idx[j] for j in np.argsort(-scores)[:5]}
    assert 200 in top5, "the price jump bar should be among the 5 weirdest bars"
    assert 250 in top5, "the volume spike bar should be among the 5 weirdest bars"


def test_zscore_rules_also_catch_them_and_stay_quiet_otherwise():
    bars = build_bars(make_ticks(n_bars=300, jump_at=200, spike_at=250))["TEST"]
    X, idx = features(bars)
    flagged = {idx[j] for j in np.nonzero(zscore_flags(X))[0]}
    assert {200, 250} <= flagged
    assert len(flagged) <= 8  # not flagging a pile of normal bars
