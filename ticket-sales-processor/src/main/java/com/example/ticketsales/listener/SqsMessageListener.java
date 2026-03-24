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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    @Value("${kafka.topics.payment-request}")
    private String paymentRequestTopic;

    @Scheduled(fixedDelay = 100)
    public void poll() {
        int maxToFetch = 10;
        // 1. Try to consume concurrency tokens
        long concurrencyConsumed = proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).tryConsumeAsMuchAsPossible(maxToFetch);
        if (concurrencyConsumed <= 0) return;

        // 2. Try to consume rate (TPS) tokens up to what we got from concurrency
        long rateConsumed = proxyManager.builder().build("payment-rate-bucket", rateConfiguration).tryConsumeAsMuchAsPossible(concurrencyConsumed);

        // Return excess concurrency tokens if we didn't get enough rate tokens
        if (rateConsumed < concurrencyConsumed) {
            proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).addTokens(concurrencyConsumed - rateConsumed);
        }

        if (rateConsumed <= 0) return;

        // We will keep track of how many concurrency tokens need to be returned manually (those for which we didn't successfully publish to Kafka)
        long concurrencyTokensToReturn = rateConsumed;
        try {
            String queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            List<Message> messages = sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages((int) rateConsumed)
                            .waitTimeSeconds(2)
                            .build()
            ).messages();

            for (Message msg : messages) {
                try {
                    process(msg.body());
                    // Successfully processed (or at least sent to Kafka/DynamoDB).
                    // Concurrency token will now be returned by PaymentResponseListener or leaked (handled by safety refill).
                    concurrencyTokensToReturn--;
                } catch (Exception e) {
                    log.error("Failed to process message: {}", msg.body(), e);
                    // concurrencyTokensToReturn is NOT decremented, so it will be returned in finally block
                } finally {
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                }
            }
        } catch (Exception e) {
            log.error("Error polling SQS: {}", e.getMessage());
        } finally {
            if (concurrencyTokensToReturn > 0) {
                proxyManager.builder().build("payment-concurrency-bucket", concurrencyConfiguration).addTokens(concurrencyTokensToReturn);
                log.info("Returned {} concurrency tokens to bucket", concurrencyTokensToReturn);
            }
        }
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
