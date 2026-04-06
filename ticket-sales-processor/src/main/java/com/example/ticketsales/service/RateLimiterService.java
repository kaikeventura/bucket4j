package com.example.ticketsales.service;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration rateConfiguration;

    private static final String RATE_KEY = "payment-rate-bucket";

    private final Meter meter = GlobalOpenTelemetry.getMeter("com.example.ticketsales");
    private final DoubleHistogram waitTimeHistogram = meter.histogramBuilder("bucket4j.wait_time")
            .setDescription("Time spent waiting for a token from Bucket4j")
            .setUnit("s")
            .build();

    public Bucket getRateBucket() {
        return proxyManager.builder().build(RATE_KEY, rateConfiguration);
    }

    public void consumeRateTokenBlocking() throws InterruptedException {
        long start = System.nanoTime();
        getRateBucket().asBlocking().consume(1);
        double durationSeconds = (System.nanoTime() - start) / 1e9;

        waitTimeHistogram.record(durationSeconds, Attributes.of(AttributeKey.stringKey("bucket"), RATE_KEY));
    }
}
