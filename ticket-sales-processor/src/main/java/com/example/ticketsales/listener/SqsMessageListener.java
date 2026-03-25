package com.example.ticketsales.listener;

import com.example.ticketsales.model.Payment;
import com.example.ticketsales.model.PaymentRequest;
import com.example.ticketsales.model.TicketSaleMessage;
import com.example.ticketsales.repository.PaymentRepository;
import com.example.ticketsales.service.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqsMessageListener {

    private final SqsClient sqsClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentRepository paymentRepository;
    private final RateLimiterService rateLimiter;
    private final AsyncTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String cachedQueueUrl;

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    @Value("${kafka.topics.payment-request}")
    private String paymentRequestTopic;

    @PostConstruct
    public void init() {
        // Start multiple concurrent long-polling workers to neutralize SQS latency.
        // Since they all coordinate through the same Redis buckets, the limits remain global.
        for (int i = 0; i < 20; i++) {
            taskExecutor.execute(this::continuousPoll);
        }
    }

    public void continuousPoll() {
        log.info("Starting continuous SQS polling loop...");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Precise blocking on Rate Token (TPS)
                rateLimiter.acquireRateTokenBlocking();

                // 2. Try Concurrency Token
                if (!rateLimiter.acquireConcurrencyToken()) {
                    // If full, return rate token and wait a bit to avoid hot-loop
                    rateLimiter.releaseRateToken();
                    Thread.sleep(10);
                    continue;
                }

                // 3. Perform Long Polling
                String queueUrl = getQueueUrl();
                List<Message> messages = sqsClient.receiveMessage(
                        ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages(1)
                                .waitTimeSeconds(20) // Max wait for efficiency
                                .build()
                ).messages();

                if (messages.isEmpty()) {
                    rateLimiter.releaseConcurrencyToken();
                    rateLimiter.releaseRateToken();
                    continue;
                }

                Message msg = messages.get(0);
                taskExecutor.execute(() -> {
                    try {
                        process(msg.body());
                    } catch (Exception e) {
                        log.error("Failed to process message: {}", msg.body(), e);
                        rateLimiter.releaseConcurrencyToken();
                    } finally {
                        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                    }
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in continuous polling loop", e);
                try {
                    Thread.sleep(1000); // Backoff on major error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private String getQueueUrl() {
        if (cachedQueueUrl == null) {
            cachedQueueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        }
        return cachedQueueUrl;
    }

    private void process(String body) throws Exception {
        long start = System.nanoTime();
        String status = "SUCCESS";

        try {
            TicketSaleMessage ticketMsg = objectMapper.readValue(body, TicketSaleMessage.class);
            log.info("Received from SQS: {}", ticketMsg);

            PaymentRequest request = new PaymentRequest();
            request.setTicketId(ticketMsg.getTicketId());
            request.setAmount(ticketMsg.getAmount());
            request.setCurrency(ticketMsg.getCurrency());
            request.setUserId(ticketMsg.getUserId());

            kafkaTemplate.send(paymentRequestTopic, objectMapper.writeValueAsString(request));
            log.info("Published PaymentRequest to Kafka: {}", request);

            Payment payment = new Payment();
            payment.setTicketId(ticketMsg.getTicketId());
            payment.setUserId(ticketMsg.getUserId());
            payment.setAmount(ticketMsg.getAmount());
            payment.setCurrency(ticketMsg.getCurrency());
            payment.setStatus("PENDING");
            payment.setTimestamp(Instant.now().toString());
            paymentRepository.save(payment);
            log.info("Saved PENDING payment for ticketId={}", ticketMsg.getTicketId());
        } catch (Exception e) {
            status = "FAILED";
            throw e;
        } finally {
            double durationSeconds = (System.nanoTime() - start) / 1e9;
            Attributes attrs = Attributes.of(
                    AttributeKey.stringKey("service_name"), "ticket-sales-processor",
                    AttributeKey.stringKey("status"), status
            );
            GlobalOpenTelemetry.getMeter("com.example.ticketsales").counterBuilder("tickets_processed_total")
                    .setDescription("Total number of tickets processed")
                    .setUnit("1")
                    .build()
                    .add(1, attrs);
            GlobalOpenTelemetry.getMeter("com.example.ticketsales").histogramBuilder("processing_duration_seconds")
                    .setDescription("Histogram of processing duration")
                    .setUnit("seconds")
                    .build()
                    .record(durationSeconds, attrs);
        }
    }
}
