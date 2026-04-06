package com.example.ticketsales.service;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration rateConfiguration;
    private Bucket rateBucket;

    private static final String RATE_KEY = "payment-rate-bucket";

    private final Meter meter = GlobalOpenTelemetry.getMeter("com.example.ticketsales");
    private final DoubleHistogram waitTimeHistogram = meter.histogramBuilder("bucket4j.wait_time")
            .setDescription("Time spent waiting for a token from Bucket4j")
            .setUnit("s")
            .build();

    @PostConstruct
    public void init() {
        this.rateBucket = proxyManager.builder().build(RATE_KEY, rateConfiguration);
    }

    public void consumeRateTokenBlocking() throws InterruptedException {
        long start = System.nanoTime();
        rateBucket.asBlocking().consume(1);
        double durationSeconds = (System.nanoTime() - start) / 1e9;

        waitTimeHistogram.record(durationSeconds, Attributes.of(AttributeKey.stringKey("bucket"), RATE_KEY));
    }
}
