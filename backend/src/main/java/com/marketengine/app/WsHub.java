package com.marketengine.app;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Pushes JSON events to every connected browser.
 *
 * Backpressure: each session is wrapped so sends are serialized, and a client that can't keep up
 * (more than 256 KB buffered, or a send blocked for over a second) gets disconnected instead of
 * slowing everyone else down. The frontend reconnects on its own.
 */
@Component
public class WsHub extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WsHub.class);

    private static final int SEND_TIME_LIMIT_MS = 1000;
    private static final int BUFFER_LIMIT_BYTES = 256 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public WsHub(ObjectMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        registry.gauge("market.ws.sessions", sessions, Map::size);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    public int sessionCount() {
        return sessions.size();
    }

    public void broadcast(Object message) {
        if (sessions.isEmpty()) return;
        TextMessage text;
        try {
            text = new TextMessage(mapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.warn("couldn't serialize websocket message: {}", e.getMessage());
            return;
        }
        for (WebSocketSession s : sessions.values()) {
            try {
                if (s.isOpen()) s.sendMessage(text);
            } catch (Exception e) {  // includes SessionLimitExceededException for slow clients
                sessions.remove(s.getId());
                try {
                    s.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException ignored) {
                    // already gone
                }
            }
        }
    }
}
