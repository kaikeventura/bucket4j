FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o load-tester .

FROM scratch
COPY --from=builder /app/load-tester /load-tester
EXPOSE 8081
ENTRYPOINT ["/load-tester"]
