package com.example.ticketsales.listener;

import com.example.ticketsales.model.Payment;
import com.example.ticketsales.model.PaymentRequest;
import com.example.ticketsales.model.TicketSaleMessage;
import com.example.ticketsales.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration concurrencyConfiguration;
    private final BucketConfiguration rateConfiguration;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String cachedQueueUrl;

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    @Value("${kafka.topics.payment-request}")
    private String paymentRequestTopic;

    @Scheduled(fixedDelay = 10)
    public void poll() {
        // 1. Try to consume 1 concurrency token
        if (!proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).tryConsume(1)) {
            return;
        }

        boolean concurrencyTokenReturned = false;
        try {
            // 2. Try to consume 1 rate (TPS) token
            if (!proxyManager.builder().build("payment-rate-bucket", rateConfiguration).tryConsume(1)) {
                proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).addTokens(1);
                concurrencyTokenReturned = true;
                return;
            }

            String queueUrl = getQueueUrl();
            List<Message> messages = sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(1)
                            .waitTimeSeconds(2)
                            .build()
            ).messages();

            if (messages.isEmpty()) {
                proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).addTokens(1);
                proxyManager.builder().build("payment-rate-bucket", rateConfiguration).addTokens(1);
                concurrencyTokenReturned = true;
                return;
            }

            for (Message msg : messages) {
                taskExecutor.execute(() -> {
                    try {
                        process(msg.body());
                    } catch (Exception e) {
                        log.error("Failed to process message: {}", msg.body(), e);
                        proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).addTokens(1);
                    } finally {
                        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error polling SQS: {}", e.getMessage());
            if (!concurrencyTokenReturned) {
                proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).addTokens(1);
                proxyManager.builder().build("payment-rate-bucket", rateConfiguration).addTokens(1);
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
