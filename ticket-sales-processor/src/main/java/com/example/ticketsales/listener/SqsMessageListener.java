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
import io.opentelemetry.api.metrics.Meter;
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

    private final Meter meter = GlobalOpenTelemetry.getMeter("com.example.ticketsales");

    @SqsListener(value = "${aws.sqs.queue-name}", maxMessagesPerPoll = "10")
    public void process(String messageBody) throws Exception {
        // Incrementa o contador de recebimento IMEDIATAMENTE ao sair do SQS
        meter.counterBuilder("tickets.received")
                .setDescription("Total tickets received from SQS")
                .build()
                .add(1);

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
            throw e;
        } finally {
            double durationSeconds = (System.nanoTime() - start) / 1e9;
            Attributes attrs = Attributes.of(AttributeKey.stringKey("status"), status);

            meter.counterBuilder("tickets.processed")
                    .setDescription("Total tickets sent to Kafka")
                    .setUnit("1")
                    .build()
                    .add(1, attrs);

            meter.histogramBuilder("processing.duration")
                    .setDescription("Histogram of processing duration")
                    .setUnit("s")
                    .build()
                    .record(durationSeconds, attrs);
        }
    }
}
