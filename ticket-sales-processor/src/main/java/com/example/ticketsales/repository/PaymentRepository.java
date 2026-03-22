package com.example.ticketsales.repository;

import com.example.ticketsales.model.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
public class PaymentRepository {

    private final DynamoDbTable<Payment> table;

    public PaymentRepository(DynamoDbEnhancedClient client, @Value("${aws.dynamodb.table-name}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Payment.class));
    }

    public void save(Payment payment) {
        table.putItem(payment);
    }

    public Optional<Payment> findById(String ticketId) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(ticketId).build()));
    }

    public void updateStatus(String ticketId, String status, String transactionId) {
        findById(ticketId).ifPresent(payment -> {
            payment.setStatus(status);
            payment.setTransactionId(transactionId);
            table.updateItem(payment);
        });
    }
}
