#!/bin/bash

ENDPOINT=http://localhost:4566
REGION=us-east-1
AWS="aws --endpoint-url=$ENDPOINT --region=$REGION"

echo "==> Creating SQS queue..."
$AWS sqs create-queue --queue-name ticket-sales-queue || echo "Queue already exists, skipping."

echo "==> Creating DynamoDB table..."
$AWS dynamodb create-table \
  --table-name payments \
  --attribute-definitions AttributeName=ticketId,AttributeType=S \
  --key-schema AttributeName=ticketId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST || echo "Table already exists, skipping."

echo "==> Done."
