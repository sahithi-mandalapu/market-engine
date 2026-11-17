package com.marketengine.exchange;

/** The matching engine couldn't be reached or sent something we didn't understand. */
public class ExchangeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ExchangeException(String message) {
        super(message);
    }

    public ExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
