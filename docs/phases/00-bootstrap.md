# Phase 00 — Bootstrap

## Status
Completed and validated on 2026-08-24.

## Objective
Validar que o repositório está compilável, testável, executável localmente e via Docker Compose, conectado a PostgreSQL, com Flyway, jOOQ, Caddy e health check, sem antecipar funcionalidades futuras.

## In scope
- monorepo inicial;
- `backend/` como único projeto Gradle/Kotlin;
- JDK 21;
- Ktor;
- PostgreSQL;
- Flyway;
- jOOQ;
- Docker Compose;
- Caddy;
- `.env.example`;
- health endpoint;
- logging básico;
- testes de bootstrap.

## Out of scope
- Twilio;
- Groq/Ollama;
- Transaction;
- CashFlowEntry;
- áudio/imagem;
- dashboard;
- filas externas;
- Redis/Kafka/RabbitMQ.

## Repository structure
```text
sova/
├── AGENTS.md
├── README.md
├── docs/
│   ├── SPEC.md
│   ├── ARCHITECTURE.md
│   ├── ROADMAP.md
│   ├── ADR/
│   └── phases/
│       └── 00-bootstrap.md
├── backend/
├── infra/
├── docker-compose.yml
└── .env.example
```

`backend/` deve permanecer um único módulo Gradle nesta fase.
O diretório `frontend/` será criado apenas quando a fase do dashboard for iniciada.

## Required baseline
- Kotlin + JDK 21;
- Ktor;
- Gradle Kotlin DSL;
- PostgreSQL JDBC;
- Flyway;
- jOOQ;
- kotlinx.serialization.

## Configuration
Configuração via environment variables. Nenhum secret real no Git.

Variáveis consumidas diretamente pelo backend:
```text
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
SOVA_HTTP_HOST
SOVA_HTTP_PORT
```

`SOVA_HTTP_HOST` e `SOVA_HTTP_PORT` possuem, respectivamente, os defaults `0.0.0.0` e `8080`.

Variáveis de entrada do Docker Compose e do Caddy:
```text
DATABASE_NAME
DATABASE_USER
DATABASE_PASSWORD
SOVA_HTTP_PORT
SOVA_HTTPS_PORT
SOVA_ADDRESS
```

O Compose deriva internamente `DATABASE_URL` usando o hostname do serviço `postgres`.

## PostgreSQL
- conexão funcional;
- volume persistente;
- conexões JDBC de curta duração para as verificações do bootstrap;
- sem schema financeiro especulativo.

Um pool de conexões deve ser introduzido somente quando os fluxos persistentes das fases seguintes justificarem seu uso e dimensionamento.

## Flyway
- migrations versionadas;
- diretório documentado;
- migrations aplicáveis em banco vazio;
- migrations aplicadas nunca devem ser editadas.

## jOOQ
- dependência e dialeto PostgreSQL configurados;
- uso restrito à consulta tipada de readiness nesta fase;
- sem geração de código enquanto não existe schema financeiro estável.

A configuração de code generation será adicionada junto ao primeiro schema de domínio que realmente se beneficie dela. O código gerado deverá permanecer fora do domínio.

## Health endpoint
```http
GET /health
```

Resposta mínima:
```json
{"status":"UP","components":{"database":"UP"}}
```

Endpoints disponíveis:

- `GET /health/live`: confirma que o processo está vivo;
- `GET /health/ready`: confirma aplicação e PostgreSQL prontos;
- `GET /health`: alias de readiness usado pelo smoke test.

## Docker Compose
```bash
docker compose up -d
```

Containers esperados:
```text
sova-api
postgres
caddy
```

Caddy faz parte da stack local padrão e expõe a API em `http://localhost:8080`.

## Tests
Cobertura implementada:
- liveness retorna HTTP 200;
- readiness retorna HTTP 200 quando o PostgreSQL responde;
- readiness retorna HTTP 503 quando o PostgreSQL não responde;
- configuração válida é carregada;
- defaults HTTP são aplicados;
- variáveis obrigatórias ausentes falham de forma compreensível;
- porta inválida falha de forma compreensível;
- migration aplica em PostgreSQL vazio e readiness funciona.

A suíte usa PostgreSQL embarcado e não depende de PostgreSQL instalado manualmente na máquina.

## Acceptance Criteria

### AC-00-01
`./gradlew test` deve concluir com sucesso.

**PASS** — 8 testes concluídos sem falhas.

### AC-00-02
`docker compose up -d` deve iniciar PostgreSQL e API sem intervenção manual.

**PASS** — PostgreSQL e API atingiram estado saudável; Caddy iniciou corretamente.

### AC-00-03
`GET /health` deve retornar HTTP 200.

**PASS** — validado através do Caddy com status e database `UP`.

### AC-00-04
A aplicação deve conectar ao PostgreSQL.

**PASS** — readiness e teste de integração executaram consulta real no PostgreSQL.

### AC-00-05
Migrations Flyway pendentes devem aplicar em banco vazio sem erro.

**PASS** — `V1__bootstrap.sql` aplicada e registrada em `flyway_schema_history`.

### AC-00-06
Nenhuma credencial real deve estar versionada.

**PASS** — somente valores locais de exemplo estão presentes e `.env` está ignorado.

### AC-00-07
`backend/` deve permanecer um único módulo Gradle.

**PASS** — existe um único projeto Gradle em `backend/`.

### AC-00-08
Não deve existir implementação prematura de Twilio, Groq ou domínio financeiro.

**PASS** — nenhuma capacidade de fase posterior foi implementada.

## Validation checklist
- [x] JDK 21
- [x] Kotlin
- [x] Ktor inicia
- [x] Gradle Wrapper
- [x] PostgreSQL no Compose
- [x] volume persistente
- [x] Flyway
- [x] jOOQ
- [x] `/health`
- [x] `.env.example`
- [x] `.env` ignorado
- [x] Caddy inicial
- [x] testes passam
- [x] Compose sobe
- [x] README explica execução
- [x] único módulo Gradle
- [x] sem infra distribuída desnecessária

## Definition of Done
Todos os ACs atendidos, build verde, aplicação reproduzível, PostgreSQL operacional, migrations funcionando, health disponível, documentação suficiente e nenhuma violação arquitetural.

**Resultado:** atendida em 2026-08-24. A stack também foi reiniciada sem remoção dos volumes, preservando o histórico Flyway e retornando ao estado saudável.

## Codex instruction
> Valide a implementação atual contra `docs/phases/00-bootstrap.md`, respeitando `AGENTS.md`, `docs/ARCHITECTURE.md` e os ADRs. Corrija apenas divergências necessárias. Não implemente fases seguintes. Ao finalizar, reporte cada AC como PASS ou FAIL e liste alterações realizadas.
