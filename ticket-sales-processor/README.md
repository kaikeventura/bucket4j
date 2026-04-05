# Ticket Sales Processor (Java)

Este é o componente central do sistema, desenvolvido com **Spring Boot 3.2** e **Java 21**. Sua principal responsabilidade é atuar como um orquestrador resiliente, consumindo pedidos de venda de ingressos do AWS SQS e gerenciando o fluxo de pagamento via Kafka.

## 🚀 Principais Funcionalidades

-   **Processamento Assíncrono**: Consome mensagens do SQS e publica solicitações no Kafka de forma não bloqueante.
-   **Virtual Threads**: Utiliza Java 21 Virtual Threads para escalabilidade eficiente de I/O.
-   **Rate Limiting Distribuído**:
    -   Implementado com **Bucket4j** e **Redis**.
    -   **TPS Controlado**: Garante uma vazão linear de processamento (ex: 50 TPS) para não sobrecarregar parceiros externos.
    -   **Controle de Concorrência**: Limita o número de mensagens "em voo" (in-flight) simultâneas.
-   **Persistência**: Armazena o estado do pagamento (PENDING, SUCCESS, FAILED) no **DynamoDB**.
-   **Observabilidade Nativa**: Instrumentação completa com OpenTelemetry (Metrics, Traces e Logs).

## 🛠️ Tecnologias

-   **Spring Boot 3.2**
-   **Java 21**
-   **Spring Kafka**
-   **AWS SDK v2** (SQS & DynamoDB)
-   **Bucket4j** (Redis integration)
-   **Lettuce** (Redis Client)
-   **Lombok**

## ⚙️ Configurações (Variáveis de Ambiente)

Abaixo estão as principais variáveis que podem ser configuradas no `docker-compose.yml` ou no ambiente:

| Variável | Descrição | Valor Padrão |
| :--- | :--- | :--- |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Endereço do broker Kafka | `localhost:9092` |
| `SPRING_DATA_REDIS_HOST` | Host do servidor Redis | `localhost` |
| `SPRING_DATA_REDIS_PORT` | Porta do servidor Redis | `6379` |
| `AWS_ENDPOINT` | Endpoint para SQS/DynamoDB (Localstack) | `http://localhost:4566` |
| `AWS_REGION` | Região AWS | `us-east-1` |
| `RATELIMIT_TPS` | Limite de Transações por Segundo | `50` |
| `RATELIMIT_CONCURRENCY` | Limite de mensagens em processamento | `500` |

## 🔄 Fluxo de Processamento

1.  **Guarded Polling**: O `SqsMessageListener` tenta adquirir tokens de concorrência antes de buscar mensagens no SQS.
2.  **Consumo**: As mensagens são lidas do SQS (Long Polling).
3.  **Rate Limit**: Antes de processar cada mensagem individualmente, a aplicação adquire um token de vazão (TPS).
4.  **Kafka Publish**: Envia um `PaymentRequest` para o tópico `payment-request`.
5.  **Persistência**: Salva o registro inicial no DynamoDB como `PENDING`.
6.  **Async Callback**: O `PaymentResponseListener` aguarda a resposta no tópico `payment-response`, atualiza o DynamoDB e **libera o token de concorrência**.

## 📊 Métricas Customizadas

Além das métricas padrão do Spring Boot (JVM, HTTP), a aplicação exporta:
-   `tickets_processed_total`: Contador de tickets processados por status.
-   `processing_duration_seconds`: Histograma do tempo de processamento.
-   Métricas do Bucket4j (vazão de tokens e tokens disponíveis).
