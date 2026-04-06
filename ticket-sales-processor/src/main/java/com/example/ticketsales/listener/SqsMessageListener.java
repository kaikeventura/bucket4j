package com.example.ticketsales.listener;

import com.example.ticketsales.model.Payment;
import com.example.ticketsales.model.PaymentRequest;
import com.example.ticketsales.model.TicketSaleMessage;
import com.example.ticketsales.repository.PaymentRepository;
import com.example.ticketsales.service.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqsMessageListener {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentRepository paymentRepository;
    private final RateLimiterService rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kafka.topics.payment-request}")
    private String paymentRequestTopic;

    @SqsListener(value = "${aws.sqs.queue-name}", maxMessagesPerPoll = "1")
    public void process(String messageBody) throws Exception {
        // 1. Bloqueio inteligente: Se não houver token, a Virtual Thread "estaciona"
        // até que o Refill.greedy libere o próximo (ex: daqui a 100ms).
        rateLimiter.consumeRateTokenBlocking();

        long start = System.nanoTime();
        String status = "SUCCESS";

        try {
            TicketSaleMessage ticketMsg = objectMapper.readValue(messageBody, TicketSaleMessage.class);
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
            log.error("Failed to process message: {}", messageBody, e);
            // 3. Em caso de erro técnico na API externa (ou processamento),
            // NÃO capturar a exceção para que ela sofra o Visibility Timeout do SQS.
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
