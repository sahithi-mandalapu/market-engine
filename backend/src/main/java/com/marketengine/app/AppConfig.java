package com.marketengine.app;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.marketengine.core.MarketPipeline;
import com.marketengine.core.Portfolio;
import com.marketengine.exchange.MatchingEngineClient;
import com.marketengine.trading.LiquidityBot;
import com.marketengine.trading.TradingDesk;

/** Builds the plain Java objects from the core packages and hands them to Spring. */
@Configuration
public class AppConfig {

    @Bean
    public MarketPipeline marketPipeline(@Value("${market.bar-ms}") long barMs,
                                         @Value("${market.lateness-ms}") long latenessMs) {
        return new MarketPipeline(barMs, latenessMs);
    }

    @Bean
    public Portfolio portfolio(@Value("${market.starting-cash}") double startingCash) {
        return new Portfolio(startingCash);
    }

    @Bean(destroyMethod = "close")
    public MatchingEngineClient matchingEngineClient(@Value("${market.engine.host}") String host,
                                                     @Value("${market.engine.port}") int port) {
        return new MatchingEngineClient(host, port);
    }

    @Bean
    public TradingDesk tradingDesk(MatchingEngineClient engine, Portfolio portfolio) {
        return new TradingDesk(engine, portfolio);
    }

    @Bean
    public LiquidityBot liquidityBot(MatchingEngineClient engine, TradingDesk desk) {
        return new LiquidityBot(engine, desk, 1L);
    }

    /** created on startup by Spring's KafkaAdmin if it doesn't exist yet */
    @Bean
    public NewTopic ticksTopic(@Value("${market.topic}") String topic, @Value("${market.partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
