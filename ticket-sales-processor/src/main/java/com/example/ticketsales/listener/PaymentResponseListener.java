package com.example.ticketsales.listener;

import com.example.ticketsales.model.PaymentResponse;
import com.example.ticketsales.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResponseListener {

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "${kafka.topics.payment-response}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        Span span = GlobalOpenTelemetry.getTracer("com.example.ticketsales").spanBuilder("kafka.consume_payment_response").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            PaymentResponse response = objectMapper.readValue(message, PaymentResponse.class);
            span.setAttribute("ticket.id", response.getTicketId());
            span.setAttribute("payment.status", response.getStatus());
            log.info("Received PaymentResponse: {}", response);

            paymentRepository.updateStatus(response.getTicketId(), response.getStatus(), response.getTransactionId());
            log.info("Updated status={} for ticketId={}", response.getStatus(), response.getTicketId());

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            log.error("Error processing payment response", e);
        } finally {
            span.end();
        }
    }
}
