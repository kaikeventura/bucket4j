# 💻 Guia de Desenvolvimento

Este guia é destinado a desenvolvedores e agentes de IA que desejam modificar o sistema. Ele detalha os processos de build, teste e manutenção.

## 🧱 Setup do Ambiente Local

O sistema foi desenhado para rodar localmente usando **Docker Compose**. Não é necessário instalar Java, Go, Kafka ou Redis localmente; tudo está conteinerizado.

### Requisitos Mínimos:
-   Docker Desktop (ou Docker Engine)
-   8GB de RAM livre
-   4 núcleos de CPU livres

### Comandos de Build:
As aplicações são compiladas dentro dos seus respectivos contêineres Docker, mas você pode compilar localmente se tiver as ferramentas instaladas:

#### Java (Ticket Sales Processor)
-   **Build**: `./mvnw clean package`
-   **Testes**: `./mvnw test`
-   **Executar**: `./mvnw spring-boot:run`

#### Go (Payment Processor & Load Tester)
-   **Build**: `go build -o app main.go`
-   **Testes**: `go test ./...`
-   **Executar**: `go run main.go`

## 🏗️ Padrões de Projeto e Melhores Práticas

### 1. Java 21 Virtual Threads
O projeto Java utiliza Virtual Threads (`spring.threads.virtual.enabled=true`).
- **NUNCA** use `synchronized` em código que faz I/O. Use `java.util.concurrent.locks.ReentrantLock`.
- **NUNCA** bloqueie a thread principal. Use `AsyncTaskExecutor` (que já está configurado como um Virtual Thread Executor).

### 2. Kafka Compression e Acks
Devido a limitações da imagem Alpine do Docker:
- **Compression**: Use `lz4` ou `none`. **NÃO** use `snappy` (causa `UnsatisfiedLinkError`).
- **Acks**: O padrão é `acks=1` para garantir performance de linearidade de TPS.

### 3. Rate Limiting (Bucket4j)
A configuração de Rate Limit está centralizada em `RateLimitConfig.java`.
- **TPS (Tokens Per Second)**: O balde é "greedy", recarregando tokens uniformemente ao longo de 1 segundo.
- **Concurrency (Slots)**: O balde tem uma taxa de refil de segurança extremamente baixa (1 token a cada 10s). Isso força o sistema a liberar tokens manualmente através de `releaseConcurrencyTokens` no callback do Kafka.

### 4. DynamoDB (AWS SDK v2)
O DynamoDB é acessado usando a biblioteca **DynamoDbEnhancedClient**.
- Os modelos estão em `com.example.ticketsales.model`.
- A tabela `payments` usa `ticketId` como chave de partição (Partition Key).

## 🧪 Estratégia de Testes

### 1. Testes Unitários
Cada subprojeto deve manter testes unitários para sua lógica core.
- **Java**: Mockito para mocks do AWS SDK e Kafka Template.
- **Go**: Testes nativos do Go com mocks de interfaces.

### 2. Testes de Integração (Locais)
Use o **Load Tester** para validar o sistema ponta-a-ponta:
1.  Suba o ambiente: `docker-compose up -d`.
2.  Dispare carga: `curl "http://localhost:8081/load-test?count=100&workers=10"`.
3.  Valide no Grafana se as métricas de sucesso estão próximas de 100%.

## 🔧 Adicionando Novos Serviços

Ao adicionar um novo microserviço ao ecossistema:
1.  **OTel**: Instrumente com OpenTelemetry e configure para exportar via OTLP para `http://otel-collector:4318`.
2.  **Docker Compose**: Adicione o serviço e certifique-se de que ele depende de `otel-collector` e do barramento de dados necessário (Kafka/Redis).
3.  **Logs**: Garanta que o serviço logue em formato padrão (JSON ou texto simples) para que o Promtail possa coletar.
4.  **README**: Adicione um `README.md` no novo subdiretório seguindo o padrão dos demais.

---
Para dúvidas arquiteturais mais profundas, consulte [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md).
