package com.example.ticketsales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketSalesProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketSalesProcessorApplication.class, args);
    }
}
