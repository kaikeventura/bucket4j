# Payment Processor Fake (Go)

Este serviço é um simulador de processador de pagamentos externo de alto desempenho desenvolvido em **Go 1.22**. Ele é projetado para atuar como um consumidor rápido do Kafka, simulando a lógica de negócios de um gateway de pagamento real.

## 🚀 Principais Funcionalidades

-   **Processamento Assíncrono**: Consome mensagens do tópico `payment-request` e publica o resultado no `payment-response`.
-   **Simulação de Pagamento**: Implementa lógica aleatória para determinar o status do pagamento (`SUCCESS` ou `FAILED`).
-   **Performance**: Utiliza a biblioteca `segmentio/kafka-go` para alta performance e latência mínima de processamento.
-   **Instrumentação Nativa**: Implementa OpenTelemetry (Tracing e Métricas) diretamente no código Go.
-   **Batch Processing**: Configurado para otimizar o envio de respostas no Kafka em lotes, evitando o overhead de I/O por mensagem única.

## 🛠️ Tecnologias

-   **Go 1.22**
-   **Kafka-Go** (Segmentio)
-   **OpenTelemetry SDK**
-   **JSON Marshaling**

## ⚙️ Configurações (Variáveis de Ambiente)

| Variável | Descrição | Valor Padrão |
| :--- | :--- | :--- |
| `KAFKA_BROKER` | Endereço do broker Kafka | `localhost:9092` |
| `REQUEST_TOPIC` | Tópico de solicitação | `payment-request` |
| `RESPONSE_TOPIC` | Tópico de resposta | `payment-response` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Endpoint do Collector OTel | `http://otel-collector:4318` |
| `OTEL_SERVICE_NAME` | Nome do serviço para traces | `payment-processor-fake` |

## 🔄 Fluxo de Processamento Interno

1.  **Consume**: Lê mensagens do Kafka no grupo de consumidores `payment-processor-group`.
2.  **Telemetry**: Inicia um Span do Tracer OTel para cada mensagem processada, permitindo o rastreamento distribuído fim-a-fim.
3.  **Lógica Fake**: Sorteia o status do pagamento entre `SUCCESS` e `FAILED`.
4.  **Publish**: Publica o resultado (incluindo o `TransactionID` simulado) no tópico de resposta do Kafka.
5.  **Métricas**: Registra contagem de pagamentos processados e histograma da duração do processamento.

## 📊 Métricas Exportadas

-   `bucket4j.tickets.processed`: Contador total de pagamentos processados por status.
-   `bucket4j.processing.duration`: Histograma do tempo gasto para processar cada pagamento.
