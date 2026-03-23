FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# Run go mod tidy after copying source to ensure it detects the new imports and updates go.sum correctly
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -o payment-processor-fake .

FROM scratch
COPY --from=builder /app/payment-processor-fake /payment-processor-fake
ENTRYPOINT ["/payment-processor-fake"]
