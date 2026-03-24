package com.example.ticketsales.listener;

import com.example.ticketsales.repository.PaymentRepository;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PaymentResponseListenerTest {

    private PaymentRepository paymentRepository;
    private ProxyManager<String> proxyManager;
    private BucketConfiguration concurrencyConfiguration;
    private PaymentResponseListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        proxyManager = mock(ProxyManager.class);
        concurrencyConfiguration = mock(BucketConfiguration.class);

        RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
        when(proxyManager.builder()).thenReturn(builder);
        when(builder.build(anyString(), any(BucketConfiguration.class))).thenReturn(mock(io.github.bucket4j.distributed.BucketProxy.class));

        listener = new PaymentResponseListener(paymentRepository, proxyManager, concurrencyConfiguration);
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
