package com.example.ticketsales.listener;

import com.example.ticketsales.model.Payment;
import com.example.ticketsales.model.PaymentRequest;
import com.example.ticketsales.model.TicketSaleMessage;
import com.example.ticketsales.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
    private final Tracer tracer;
    private final LongCounter ticketsProcessedCounter;
    private final DoubleHistogram processingDurationHistogram;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    @Value("${kafka.topics.payment-request}")
    private String paymentRequestTopic;

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        String queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        List<Message> messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder().queueUrl(queueUrl).maxNumberOfMessages(10).build()
        ).messages();

        for (Message msg : messages) {
            try {
                process(msg.body());
            } catch (Exception e) {
                log.error("Failed to process message: {}", msg.body(), e);
            } finally {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
            }
        }
    }

    void process(String body) throws Exception {
        Span span = tracer.spanBuilder("sqs.process_ticket").startSpan();
        long start = System.nanoTime();
        String status = "SUCCESS";

        try (Scope ignored = span.makeCurrent()) {
            TicketSaleMessage ticketMsg = objectMapper.readValue(body, TicketSaleMessage.class);
            span.setAttribute("ticket.id", ticketMsg.getTicketId());
            span.setAttribute("ticket.amount", ticketMsg.getAmount());
            log.info("Received from SQS: {}", ticketMsg);

            PaymentRequest request = new PaymentRequest(
                    ticketMsg.getTicketId(), ticketMsg.getAmount(),
                    ticketMsg.getCurrency(), ticketMsg.getUserId());

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

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            status = "FAILED";
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            double durationSeconds = (System.nanoTime() - start) / 1e9;
            var attrs = io.opentelemetry.api.common.Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.stringKey("status"), status
            );
            ticketsProcessedCounter.add(1, attrs);
            processingDurationHistogram.record(durationSeconds, attrs);
            span.end();
        }
    }
}
