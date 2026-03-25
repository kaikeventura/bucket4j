package com.example.ticketsales.listener;

import com.example.ticketsales.repository.PaymentRepository;
import com.example.ticketsales.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class PaymentResponseListenerTest {

    private PaymentRepository paymentRepository;
    private RateLimiterService rateLimiter;
    private PaymentResponseListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        rateLimiter = mock(RateLimiterService.class);

        listener = new PaymentResponseListener(paymentRepository, rateLimiter);
    }

    @Test
    void consume_updatesStatus() throws Exception {
        String body = """
                {"ticketId":"T1","status":"SUCCESS","transactionId":"TXN-123"}
                """;
        listener.consume(body);
        verify(paymentRepository).updateStatus("T1", "SUCCESS", "TXN-123");
    }
}
