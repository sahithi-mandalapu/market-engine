package com.marketengine.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketengine.core.Tick;

/**
 * Sends ticks to Kafka. The record key is the symbol, so Kafka's default partitioner puts every tick of
 * a symbol on the same partition: per-symbol ordering is preserved and symbols spread across consumers.
 */
@Component
public class TickPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;

    public TickPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, @Value("${market.topic}") String topic) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
    }

    public void publish(Tick t) {
        ObjectNode n = mapper.createObjectNode();
        n.put("symbol", t.symbol());
        n.put("ts", t.ts());
        n.put("price", t.price());
        n.put("size", t.size());
        n.put("seq", t.seq());
        n.put("ingestMs", System.currentTimeMillis());  // so the consumer can measure end to end latency
        kafka.send(topic, t.symbol(), n.toString());
    }
}
