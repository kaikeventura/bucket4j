package com.example.ticketsales.service;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration concurrencyConfiguration;
    private final BucketConfiguration rateConfiguration;

    private static final String CONCURRENCY_KEY = "payment-concurrency-bucket";
    private static final String RATE_KEY = "payment-rate-bucket";

    public Bucket getConcurrencyBucket() {
        return proxyManager.builder().build(CONCURRENCY_KEY, concurrencyConfiguration);
    }

    public Bucket getRateBucket() {
        return proxyManager.builder().build(RATE_KEY, rateConfiguration);
    }

    public boolean acquireConcurrencyToken() {
        return getConcurrencyBucket().tryConsume(1);
    }

    public void releaseConcurrencyToken() {
        getConcurrencyBucket().addTokens(1);
    }

    public void acquireRateTokenBlocking() throws InterruptedException {
        getRateBucket().asBlocking().consume(1);
    }

    public void releaseRateToken() {
        getRateBucket().addTokens(1);
    }
}
