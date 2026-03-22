package com.example.ticketsales.listener;

import com.example.ticketsales.model.Payment;
import com.example.ticketsales.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SqsMessageListenerTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private PaymentRepository paymentRepository;
    private SqsMessageListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        paymentRepository = mock(PaymentRepository.class);
        listener = new SqsMessageListener(mock(SqsClient.class), kafkaTemplate, paymentRepository);
        setField(listener, "paymentRequestTopic", "payment-request");
        setField(listener, "queueName", "ticket-sales-queue");
    }

    @Test
    void process_publishesToKafkaAndSavesPendingPayment() throws Exception {
        String body = """
                {"ticketId":"T1","amount":99.9,"currency":"USD","userId":"U1"}
                """;

        listener.process(body);

        verify(kafkaTemplate).send(eq("payment-request"), contains("T1"));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getTicketId()).isEqualTo("T1");
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
