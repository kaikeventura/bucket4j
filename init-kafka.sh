#!/bin/bash

KAFKA="kafka-topics --bootstrap-server localhost:9092"

echo "==> Waiting for Kafka broker..."
until $KAFKA --list > /dev/null 2>&1; do
  echo "Broker not ready, retrying in 3s..."
  sleep 3
done

echo "==> Creating Kafka topics..."
$KAFKA --create --if-not-exists --topic payment-request --partitions 3 --replication-factor 1
$KAFKA --create --if-not-exists --topic payment-response --partitions 3 --replication-factor 1

echo "==> Topics created:"
$KAFKA --list
