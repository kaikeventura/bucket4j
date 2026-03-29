package com.example.ticketsales.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.time.Duration;
import java.util.concurrent.Executors;

@Configuration
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${ratelimit.tps:50}")
    private int tpsLimit;

    @Value("${ratelimit.concurrency:50}")
    private int concurrencyLimit;

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build());
    }

    @Bean
    public ProxyManager<String> proxyManager(RedisClient redisClient) {
        StatefulRedisConnection<String, byte[]> connection = redisClient
                .connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.none())
                .build();
    }

    @Bean
    public BucketConfiguration concurrencyConfiguration() {
        // Concurrency bucket with a safety refill of 1 token every 1 second.
        // This allows faster recovery from leaks while maintaining the 100 limit.
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(concurrencyLimit, Refill.greedy(1, Duration.ofSeconds(1))))
                .build();
    }

    @Bean
    public BucketConfiguration rateConfiguration() {
        // For linear TPS, we use greedy refill matching the TPS limit.
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(tpsLimit, Refill.greedy(tpsLimit, Duration.ofSeconds(1))))
                .build();
    }

    @Bean
    public AsyncTaskExecutor taskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
