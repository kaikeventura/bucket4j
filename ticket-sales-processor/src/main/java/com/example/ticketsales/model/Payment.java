package com.example.ticketsales.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Data
@NoArgsConstructor
@DynamoDbBean
public class Payment {
    private String ticketId;
    private String userId;
    private Double amount;
    private String currency;
    private String status;
    private String transactionId;
    private String timestamp;

    @DynamoDbPartitionKey
    public String getTicketId() { return ticketId; }
}
