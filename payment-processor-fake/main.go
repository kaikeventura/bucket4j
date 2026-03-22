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
		msg, err := reader.ReadMessage(context.Background())
		if err != nil {
			log.Printf("read error: %v", err)
			continue
		}

		var req PaymentRequest
		if err := json.Unmarshal(msg.Value, &req); err != nil {
			log.Printf("parse error: %v — value: %s", err, string(msg.Value))
			continue
		}

		resp := simulatePayment(req)
		log.Printf("Processed ticketId=%s → status=%s txn=%s", resp.TicketId, resp.Status, resp.TransactionId)

		respJSON, _ := json.Marshal(resp)
		if err := writer.WriteMessages(context.Background(), kafka.Message{Value: respJSON}); err != nil {
			log.Printf("publish error: %v", err)
		}
	}
}
