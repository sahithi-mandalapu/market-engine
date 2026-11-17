package com.marketengine.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.marketengine.app")
@EnableScheduling
public class MarketEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketEngineApplication.class, args);
    }
}
