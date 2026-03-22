package com.example.ticketsales.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {
    private String ticketId;
    private Double amount;
    private String currency;
    private String userId;
}
