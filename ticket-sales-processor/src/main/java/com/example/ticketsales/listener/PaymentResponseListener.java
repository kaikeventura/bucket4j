package com.example.ticketsales.listener;

import com.example.ticketsales.model.PaymentResponse;
import com.example.ticketsales.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public void consume(String message) throws Exception {
        PaymentResponse response = objectMapper.readValue(message, PaymentResponse.class);
        log.info("Received PaymentResponse: {}", response);

        paymentRepository.updateStatus(response.getTicketId(), response.getStatus(), response.getTransactionId());
        log.info("Updated status={} for ticketId={}", response.getStatus(), response.getTicketId());
    }
}
