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
        // Start workers for long-polling
        // Increased number of workers to 20 to ensure we're never waiting for a polling slot
        for (int i = 0; i < 20; i++) {
            taskExecutor.execute(this::continuousPoll);
        }
    }

    public void continuousPoll() {
        log.info("Starting SQS polling loop...");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Acquire up to 10 concurrency slots first to guard SQS fetch
                long acquiredConcurrency = rateLimiter.acquireConcurrencyTokens(10);
                if (acquiredConcurrency <= 0) {
                    log.debug("No concurrency tokens available, backing off...");
                    Thread.sleep(100);
                    continue;
                }

                String queueUrl = getQueueUrl();
                List<Message> messages = sqsClient.receiveMessage(
                        ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages((int) acquiredConcurrency)
                                .waitTimeSeconds(20)
                                .build()
                ).messages();

                // 2. Return unused concurrency slots if SQS returned fewer messages
                if (messages.size() < acquiredConcurrency) {
                    long unused = acquiredConcurrency - messages.size();
                    log.debug("Returning {} unused concurrency tokens", unused);
                    rateLimiter.releaseConcurrencyTokens(unused);
                }

                if (messages.isEmpty()) {
                    continue;
                }

                for (Message msg : messages) {
                    taskExecutor.execute(() -> {
                        boolean shouldReleaseConcurrency = true;
                        try {
                            // 3. Acquire rate token (linear TPS) immediately before processing
                            rateLimiter.consumeRateTokenBlocking();

                            // process() returns true if the message was successfully sent to Kafka
                            if (process(msg.body())) {
                                shouldReleaseConcurrency = false;
                            }
                        } catch (InterruptedException e) {
                            log.warn("Processing interrupted for message: {}", msg.messageId());
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.error("Failed to process message: {}", msg.body(), e);
                        } finally {
                            if (shouldReleaseConcurrency) {
                                rateLimiter.releaseConcurrencyTokens(1);
                                log.debug("Concurrency token released due to failure or interruption");
                            }
                            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                                    .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                        }
                    });
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in polling loop", e);
                try {
                    Thread.sleep(1000);
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

    private boolean process(String body) throws Exception {
        long start = System.nanoTime();
        String status = "SUCCESS";
        boolean sentToKafka = false;

        try {
            TicketSaleMessage ticketMsg = objectMapper.readValue(body, TicketSaleMessage.class);
            log.info("Received from SQS: {}", ticketMsg);

            PaymentRequest request = new PaymentRequest();
            request.setTicketId(ticketMsg.getTicketId());
            request.setAmount(ticketMsg.getAmount());
            request.setCurrency(ticketMsg.getCurrency());
            request.setUserId(ticketMsg.getUserId());

            kafkaTemplate.send(paymentRequestTopic, objectMapper.writeValueAsString(request));
            sentToKafka = true;
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
            return true;
        } catch (Exception e) {
            status = "FAILED";
            // If we failed BEFORE sending to Kafka, we must signal the caller to release the token.
            // If we failed AFTER sending to Kafka (e.g. repo save error), the token will be released by PaymentResponseListener.
            if (sentToKafka) {
                log.warn("Kafka send succeeded but processing failed afterwards. Token will be released by async response listener.");
                return true;
            }
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
