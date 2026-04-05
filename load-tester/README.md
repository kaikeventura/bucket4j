# Load Tester (Go)

Este é o utilitário de carga do sistema, desenvolvido em **Go 1.22**. Ele é responsável por inundar a fila SQS (`ticket-sales-queue`) com mensagens simuladas de venda de ingressos para testar a escalabilidade e os limites do sistema de Rate Limiting.

## 🚀 Principais Funcionalidades

-   **Endpoint HTTP**: Disponibiliza uma API REST para disparar testes de carga sob demanda.
-   **Concorrência Configurável**: Permite definir o número de workers simultâneos que enviarão as mensagens para o SQS.
-   **Mensagens Aleatórias**: Gera tickets, usuários e valores aleatórios para cada mensagem enviada.
-   **Localstack Ready**: Integrado com o Localstack para simular o ambiente AWS localmente.

## 🛠️ Tecnologias

-   **Go 1.22**
-   **AWS SDK v2 for Go**
-   **Go Routines** (Concorrência massiva)
-   **JSON Marshaling**

## ⚙️ Configurações (Variáveis de Ambiente)

| Variável | Descrição | Valor Padrão |
| :--- | :--- | :--- |
| `AWS_ENDPOINT` | Endpoint para SQS (Localstack) | `http://localhost:4566` |
| `AWS_REGION` | Região AWS | `us-east-1` |
| `QUEUE_NAME` | Nome da fila SQS de destino | `ticket-sales-queue` |
| `PORT` | Porta HTTP do servidor | `8081` |

## 🕹️ Como Usar (Exemplos de CURL)

O Load Tester expõe um endpoint principal: `GET /load-test`.

### 1. Disparar 100 mensagens com 10 workers
Este comando enviará 100 mensagens para o SQS usando 10 workers simultâneos.
```bash
curl "http://localhost:8081/load-test?count=100&workers=10"
```

### 2. Disparar 1000 mensagens com 50 workers
Ideal para testar o comportamento do Rate Limit do `ticket-sales-processor`.
```bash
curl "http://localhost:8081/load-test?count=1000&workers=50"
```

### 3. Resposta Esperada
O serviço retornará um JSON com o resumo da carga:
```json
{
  "total": 100,
  "success": 100,
  "failed": 0,
  "elapsed": "1.234s"
}
```

## 🔄 Fluxo de Geração de Carga

1.  **Request Receive**: O servidor Go recebe a requisição HTTP com `count` e `workers`.
2.  **Worker Pool**: Cria um pool de workers usando Go Routines.
3.  **Queue Injection**: Cada worker gera uma mensagem aleatória e a envia para o SQS.
4.  **Reporting**: Ao finalizar, calcula o tempo decorrido e a taxa de sucesso/falha.
