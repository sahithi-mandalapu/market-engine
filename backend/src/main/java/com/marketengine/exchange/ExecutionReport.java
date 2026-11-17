package com.marketengine.exchange;

import java.util.List;

public record ExecutionReport(long orderId, boolean accepted, String reason, List<Fill> fills, long remaining,
                              boolean resting) {

    public static ExecutionReport rejected(long id, String reason) {
        return new ExecutionReport(id, false, reason, List.of(), 0, false);
    }
}
