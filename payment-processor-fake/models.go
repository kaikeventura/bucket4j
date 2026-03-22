package main

type PaymentRequest struct {
	TicketId string  `json:"ticketId"`
	Amount   float64 `json:"amount"`
	Currency string  `json:"currency"`
	UserId   string  `json:"userId"`
}

type PaymentResponse struct {
	TicketId      string `json:"ticketId"`
	Status        string `json:"status"`
	TransactionId string `json:"transactionId"`
}
