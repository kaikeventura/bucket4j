# 🤖 AGENTS.md - Guia para Agentes de IA

Este arquivo serve como o ponto de entrada principal para qualquer Agente de IA (como Devin, Jules ou Copilot) que esteja trabalhando neste repositório. Ele fornece o contexto de alto nível e as diretrizes necessárias para entender e modificar o sistema de forma segura.

## 📖 Visão Geral do Sistema
Este é um ecossistema de processamento de pagamentos resiliente, focado em alta disponibilidade e controle de vazão (Rate Limiting). O sistema é composto por:
1.  **Ticket Sales Processor (Java)**: Orquestrador central com Bucket4j + Redis.
2.  **Payment Processor Fake (Go)**: Simulador de gateway de pagamento via Kafka.
3.  **Load Tester (Go)**: Gerador de carga para a fila SQS.

## 🏗️ Guia de Arquitetura
A arquitetura é **orientada a eventos** (Event-Driven) e utiliza o padrão **Guarded Polling** para gerenciar a pressão sobre os consumidores. Para detalhes profundos sobre os fluxos de dados, componentes e padrões de design, consulte:
👉 [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)

## 📊 Observabilidade e Debugging
O sistema está totalmente instrumentado com OpenTelemetry. Para entender como monitorar o estado do sistema, visualizar métricas de Rate Limit ou depurar problemas de latência usando Traces:
👉 [docs/OBSERVABILITY.md](./docs/OBSERVABILITY.md)

## 💻 Desenvolvimento e Manutenção
Se você for realizar alterações no código, siga as diretrizes de desenvolvimento (padrões de codificação, como rodar testes e como adicionar novas dependências):
👉 [docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md)

## 💡 Dicas para a IA
- **Virtual Threads**: O projeto Java usa Java 21 com Virtual Threads habilitadas. Evite usar `synchronized` ou bibliotecas de bloqueio que possam causar "pinning" de threads.
- **Kafka Producer**: O produtor Kafka no Java está configurado para `acks=1` e `compression=lz4`. Não altere para `snappy` pois a imagem Docker Alpine não suporta nativamente.
- **Rate Limit**: O sistema usa um balde de concorrência e um balde de taxa (TPS). Se você modificar o limite de TPS, lembre-se de ajustar a `VisibilityTimeout` do SQS para ser pelo menos 2x o tempo esperado de processamento.
- **Localstack**: SQS e DynamoDB rodam no Localstack. Use o `init-aws.sh` como referência para nomes de filas e tabelas.

---
Este repositório foi desenhado para ser auto-explicativo através de sua infraestrutura como código (Docker Compose) e sua instrumentação. Sempre verifique os dashboards no Grafana após aplicar mudanças de performance.
