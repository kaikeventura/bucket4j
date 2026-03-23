package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

type TicketSaleMessage struct {
	TicketId string  `json:"ticketId"`
	Amount   float64 `json:"amount"`
	Currency string  `json:"currency"`
	UserId   string  `json:"userId"`
}

type Result struct {
	Total   int    `json:"total"`
	Success int64  `json:"success"`
	Failed  int64  `json:"failed"`
	Elapsed string `json:"elapsed"`
}

var sqsClient *sqs.Client
var queueUrl string

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func randomMessage(i int) TicketSaleMessage {
	currencies := []string{"USD", "BRL", "EUR"}
	return TicketSaleMessage{
		TicketId: fmt.Sprintf("TICKET-%d-%d", i, time.Now().UnixNano()),
		Amount:   float64(rand.Intn(500)+1) + rand.Float64(),
		Currency: currencies[rand.Intn(len(currencies))],
		UserId:   fmt.Sprintf("USER-%d", rand.Intn(1000)),
	}
}

func main() {
	endpoint := getEnv("AWS_ENDPOINT", "http://localhost:4566")
	region := getEnv("AWS_REGION", "us-east-1")
	queueName := getEnv("QUEUE_NAME", "ticket-sales-queue")
	port := getEnv("PORT", "8081")

	ctx := context.Background()

	// AWS Client setup
	resolver := aws.EndpointResolverWithOptionsFunc(func(service, reg string, opts ...interface{}) (aws.Endpoint, error) {
		return aws.Endpoint{
			URL:               endpoint,
			HostnameImmutable: true,
			SigningRegion:     region,
		}, nil
	})

	cfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion(region),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
		config.WithEndpointResolverWithOptions(resolver),
	)
	if err != nil {
		log.Fatalf("failed to load AWS config: %v", err)
	}

	sqsClient = sqs.NewFromConfig(cfg)

	// Ensure queue URL is available
	urlOut, err := sqsClient.GetQueueUrl(ctx, &sqs.GetQueueUrlInput{QueueName: &queueName})
	if err != nil {
		log.Fatalf("failed to get queue URL: %v", err)
	}
	queueUrl = *urlOut.QueueUrl
	log.Printf("Queue URL: %s", queueUrl)

	http.HandleFunc("/load-test", func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		countStr := query.Get("count")
		if countStr == "" {
			http.Error(w, "missing 'count' query param", http.StatusBadRequest)
			return
		}
		count, err := strconv.Atoi(countStr)
		if err != nil || count <= 0 {
			http.Error(w, "'count' must be a positive integer", http.StatusBadRequest)
			return
		}

		workers := 100
		if wStr := query.Get("workers"); wStr != "" {
			if w, err := strconv.Atoi(wStr); err == nil && w > 0 {
				workers = w
			}
		}

		log.Printf("Starting load test: %d messages, %d workers", count, workers)
		start := time.Now()

		var success, failed atomic.Int64
		jobs := make(chan int, count)
		var wg sync.WaitGroup

		for i := 0; i < workers; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for j := range jobs {
					msg := randomMessage(j)
					body, _ := json.Marshal(msg)
					_, err := sqsClient.SendMessage(ctx, &sqs.SendMessageInput{
						QueueUrl:    &queueUrl,
						MessageBody: aws.String(string(body)),
					})

					if err != nil {
						log.Printf("send error: %v", err)
						failed.Add(1)
					} else {
						success.Add(1)
					}
				}
			}()
		}

		for i := 0; i < count; i++ {
			jobs <- i
		}
		close(jobs)
		wg.Wait()

		res := Result{
			Total:   count,
			Success: success.Load(),
			Failed:  failed.Load(),
			Elapsed: time.Since(start).String(),
		}
		log.Printf("Load test finished: %+v", res)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(res)
	})

	log.Printf("Load Tester listening on :%s", port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}
