package com.example.ticketsales.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtelConfig {

    private static final String INSTRUMENTATION_NAME = "ticket-sales-processor";

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Bean
    public Meter meter(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeter(INSTRUMENTATION_NAME);
    }

    @Bean
    public LongCounter ticketsProcessedCounter(Meter meter) {
        return meter.counterBuilder("bucket4j.tickets.processed")
                .setDescription("Total tickets processed")
                .setUnit("{ticket}")
                .build();
    }

    @Bean
    public DoubleHistogram processingDurationHistogram(Meter meter) {
        return meter.histogramBuilder("bucket4j.processing.duration")
                .setDescription("Time to process a ticket end-to-end")
                .setUnit("s")
                .build();
    }
}
