package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"os"
	"time"

	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
)

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func simulatePayment(req PaymentRequest) PaymentResponse {
	statuses := []string{"SUCCESS", "FAILED"}
	return PaymentResponse{
		TicketId:      req.TicketId,
		Status:        statuses[rand.Intn(len(statuses))],
		TransactionId: fmt.Sprintf("TXN-%d", time.Now().UnixNano()),
	}
}

func main() {
	ctx := context.Background()

	shutdown, err := setupOtel(ctx)
	if err != nil {
		log.Fatalf("otel setup: %v", err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdown(shutdownCtx); err != nil {
			log.Printf("otel shutdown: %v", err)
		}
	}()

	tracer := otel.Tracer("payment-processor-fake")
	meter := otel.Meter("payment-processor-fake")

	counter, _ := meter.Int64Counter("bucket4j.tickets.processed",
		metric.WithDescription("Total payments processed by fake processor"),
	)
	histogram, _ := meter.Float64Histogram("bucket4j.processing.duration",
		metric.WithDescription("Payment processing duration"),
		metric.WithUnit("s"),
	)

	broker := getEnv("KAFKA_BROKER", "localhost:9092")
	requestTopic := getEnv("REQUEST_TOPIC", "payment-request")
	responseTopic := getEnv("RESPONSE_TOPIC", "payment-response")

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: []string{broker},
		Topic:   requestTopic,
		GroupID: "payment-processor-group",
	})
	defer reader.Close()

	writer := kafka.NewWriter(kafka.WriterConfig{
		Brokers: []string{broker},
		Topic:   responseTopic,
	})
	defer writer.Close()

	log.Printf("Consuming from topic: %s", requestTopic)

	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			log.Printf("read error: %v", err)
			continue
		}

		_, span := tracer.Start(ctx, "kafka.process_payment")
		start := time.Now()
		statusVal := "SUCCESS"

		var req PaymentRequest
		if err := json.Unmarshal(msg.Value, &req); err != nil {
			log.Printf("parse error: %v — value: %s", err, string(msg.Value))
			span.SetStatus(codes.Error, err.Error())
			span.RecordError(err)
			statusVal = "FAILED"
		} else {
			span.SetAttributes(
				attribute.String("ticket.id", req.TicketId),
				attribute.Float64("ticket.amount", req.Amount),
			)

			resp := simulatePayment(req)
			statusVal = resp.Status
			log.Printf("Processed ticketId=%s → status=%s txn=%s", resp.TicketId, resp.Status, resp.TransactionId)

			respJSON, _ := json.Marshal(resp)
			if err := writer.WriteMessages(ctx, kafka.Message{Value: respJSON}); err != nil {
				log.Printf("publish error: %v", err)
				span.SetStatus(codes.Error, err.Error())
				span.RecordError(err)
				statusVal = "FAILED"
			} else {
				span.SetStatus(codes.Ok, "")
			}
		}

		attrs := metric.WithAttributes(attribute.String("status", statusVal))
		counter.Add(ctx, 1, attrs)
		histogram.Record(ctx, time.Since(start).Seconds(), attrs)
		span.End()
	}
}
