# ADR 0001 — Modular Monolith

## Status

Accepted

## Contexto

O Sova será utilizado inicialmente por dois usuários, em uma única Hetzner CX23 com 2 vCPU e 4 GB de RAM.

O sistema precisa integrar:

- WhatsApp via Twilio;
- núcleo financeiro;
- PostgreSQL;
- IA externa;
- processamento futuro de áudio, imagem e extratos;
- dashboard web.

O volume inicial é baixo e não existem requisitos de escala distribuída.

## Decisão

O backend será implementado como um **modular monolith** em Kotlin/Ktor.

Os módulos devem ser separados por responsabilidade e domínio, mas executados inicialmente no mesmo processo/aplicação.

Estrutura conceitual:

```text
domain
application
infrastructure
delivery
```

O domínio não depende de frameworks ou integrações externas.

## Alternativas consideradas

### Microservices

Rejeitado inicialmente.

Motivos:

- mais processos;
- maior consumo de RAM;
- maior complexidade operacional;
- observabilidade distribuída desnecessária;
- contratos de rede prematuros;
- custo de deployment e troubleshooting incompatível com o tamanho atual do projeto.

### Monolith sem modularização

Rejeitado.

Motivo:

Embora simples no início, tende a misturar domínio, HTTP, Twilio, IA e persistência, dificultando testes e futura evolução.

## Consequências

### Positivas

- menor custo operacional;
- deploy simples;
- fácil execução em Docker Compose;
- menor consumo de recursos;
- transações locais simples;
- desenvolvimento mais rápido;
- separação suficiente para futura extração de componentes.

### Negativas

- módulos compartilham processo;
- falha grave do processo pode afetar múltiplas capacidades;
- será necessário manter disciplina arquitetural para evitar acoplamento indevido.

## Regras derivadas

Não introduzir sem necessidade comprovada:

- Kafka;
- RabbitMQ;
- Redis;
- service mesh;
- Kubernetes;
- múltiplos serviços backend.

Processamento assíncrono deverá usar PostgreSQL inicialmente.

A extração de serviços só deverá ocorrer quando houver um problema real que justifique a mudança.
