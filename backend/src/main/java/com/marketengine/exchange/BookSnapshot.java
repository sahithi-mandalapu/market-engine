package com.marketengine.exchange;

import java.util.List;

public record BookSnapshot(List<Level> bids, List<Level> asks) {
    public record Level(double price, long qty, int orders) {
    }
}
