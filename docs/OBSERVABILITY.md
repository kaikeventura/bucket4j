# 📊 Guia de Observabilidade

O sistema utiliza uma stack moderna de observabilidade baseada em **OpenTelemetry (OTel)**, proporcionando visibilidade completa sobre o desempenho e a saúde dos serviços distribuídos.

## 🧱 Componentes da Stack

| Componente | Função | Porta |
| :--- | :--- | :--- |
| **OTel Collector** | Coletor central de telemetria para Traces, Logs e Metrics. | `4317` / `4318` |
| **Prometheus** | Banco de dados de séries temporais para métricas. | `9090` |
| **Grafana** | Visualização de dados e dashboards. | `3000` |
| **Loki** | Agregação e consulta de logs distribuídos. | `3100` |
| **Tempo** | Armazenamento de rastreamento distribuído (Traces). | `3200` |

## 📐 Fluxo de Telemetria

1.  **Instrumentação**: Tanto as aplicações Java quanto Go estão instrumentadas com bibliotecas nativas de OpenTelemetry.
2.  **Exportação**: As aplicações exportam dados via protocolo OTLP (HTTP/Protobuf) para o **OTel Collector**.
3.  **Processamento**: O OTel Collector atua como um hub, direcionando:
    -   Métricas para o Prometheus.
    -   Logs para o Loki.
    -   Traces para o Tempo.
4.  **Visualização**: O Grafana consome essas três fontes de dados em dashboards integrados.

## 📈 Principais Dashboards (Grafana)

Acesse [http://localhost:3000](http://localhost:3000) (Login anônimo habilitado).

### 1. Sistema de Venda de Tickets
Focado no fluxo de negócios e resiliência:
-   **Throughput (TPS)**: Taxa de processamento por segundo.
-   **Latência (Latency)**: Tempo total de processamento de cada ticket.
-   **Bucket4j Health**: Estado dos tokens (concorrência e vazão). Mostra se o sistema está sendo limitado (throttling).
-   **Success vs Failure**: Proporção de pagamentos bem-sucedidos vs falhas simuladas.

### 2. Recursos de Sistema (JVM / Go)
-   **Uso de CPU e Memória**: Monitoramento por contêiner.
-   **Thread Count**: No Java, monitora o uso de Virtual Threads.
-   **GC (Garbage Collection)**: Impacto do GC no processamento Java.

## 🕵️ Rastreamento Distribuído (Distributed Tracing)

O sistema permite visualizar o caminho completo de uma transação. Ao consultar o **Tempo** no Grafana, você verá spans como:
1.  `sqs.receive`: Início do processamento no Java.
2.  `kafka.publish`: Envio da solicitação para o Kafka.
3.  `kafka.consume`: Recebimento no processador Go.
4.  `payment_logic`: Simulação do pagamento no Go.
5.  `kafka.callback`: Recebimento da resposta no Java e atualização do DynamoDB.

Essa visão é fundamental para identificar gargalos de latência entre serviços.

## 📝 Logs Centralizados (Loki)

Os logs de todas as réplicas (Java e Go) são coletados centralmente. No Grafana Explore (Loki), você pode filtrar por:
-   `service_name`: `ticket-sales-processor` ou `payment-processor-fake`.
-   `level`: INFO, WARN, ERROR.
-   `trace_id`: Para ver todos os logs relacionados a uma única transação específica.

### Dica para Depuração:
Ao encontrar um erro, copie o `trace_id` presente no log e cole no dashboard do Tempo para ver o contexto completo da transação onde o erro ocorreu.
