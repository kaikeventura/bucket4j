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
	"go.opentelemetry.io/otel/log/global"
	"go.opentelemetry.io/otel/metric"
	otellog "go.opentelemetry.io/otel/log"
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

// simple helper to emit structured log to OTel
func emitLog(logger otellog.Logger, severity otellog.Severity, body string, attrs ...otellog.KeyValue) {
	ctx := context.Background()
	r := otellog.Record{}
	r.SetTimestamp(time.Now())
	r.SetSeverity(severity)
	r.SetSeverityText(severity.String())
	r.SetBody(otellog.StringValue(body))
	r.AddAttributes(attrs...)
	logger.Emit(ctx, r)

	log.Printf("%s: %s", severity.String(), body)
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
	logger := global.Logger("payment-processor-fake")

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

	// Configura o writer com BatchTimeout menor para evitar latência de 1s
	writer := kafka.NewWriter(kafka.WriterConfig{
		Brokers:      []string{broker},
		Topic:        responseTopic,
		BatchTimeout: 10 * time.Millisecond,
	})
	defer writer.Close()

	emitLog(logger, otellog.SeverityInfo, fmt.Sprintf("Consuming from topic: %s", requestTopic))

	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			emitLog(logger, otellog.SeverityError, fmt.Sprintf("read error: %v", err))
			continue
		}

		ctx, span := tracer.Start(ctx, "kafka.process_payment")
		start := time.Now()
		statusVal := "SUCCESS"

		var req PaymentRequest
		if err := json.Unmarshal(msg.Value, &req); err != nil {
			emitLog(logger, otellog.SeverityError, fmt.Sprintf("parse error: %v — value: %s", err, string(msg.Value)))
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

			// Log processing result with attributes
			emitLog(logger, otellog.SeverityInfo,
				fmt.Sprintf("Processed ticketId=%s → status=%s txn=%s", resp.TicketId, resp.Status, resp.TransactionId),
				otellog.String("ticket_id", resp.TicketId),
				otellog.String("status", resp.Status),
				otellog.String("txn_id", resp.TransactionId),
			)

			respJSON, _ := json.Marshal(resp)
			if err := writer.WriteMessages(ctx, kafka.Message{Value: respJSON}); err != nil {
				emitLog(logger, otellog.SeverityError, fmt.Sprintf("publish error: %v", err))
				span.SetStatus(codes.Error, err.Error())
				span.RecordError(err)
				statusVal = "FAILED"
			} else {
				span.SetStatus(codes.Ok, "")
			}
		}

		counter.Add(ctx, 1, metric.WithAttributes(attribute.String("status", statusVal)))
		histogram.Record(ctx, time.Since(start).Seconds(), metric.WithAttributes(attribute.String("status", statusVal)))
		span.End()
	}
}
