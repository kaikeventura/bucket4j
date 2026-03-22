package main

import (
	"strings"
	"testing"
)

func TestSimulatePayment_validResponse(t *testing.T) {
	req := PaymentRequest{TicketId: "T1", Amount: 100.0, Currency: "BRL", UserId: "U1"}
	resp := simulatePayment(req)

	if resp.TicketId != "T1" {
		t.Errorf("expected ticketId T1, got %s", resp.TicketId)
	}
	if resp.Status != "SUCCESS" && resp.Status != "FAILED" {
		t.Errorf("unexpected status: %s", resp.Status)
	}
	if !strings.HasPrefix(resp.TransactionId, "TXN-") {
		t.Errorf("unexpected transactionId format: %s", resp.TransactionId)
	}
}
