# Sova

Sova é um assessor financeiro privado para uso pessoal, com acesso primário via WhatsApp e um painel web planejado para uma segunda etapa.

O sistema será utilizado inicialmente por dois usuários autorizados pertencentes ao mesmo household financeiro.

## Objetivos principais

O Sova deve permitir:

- cadastrar despesas e receitas por WhatsApp;
- cadastrar fluxo de caixa;
- consultar informações financeiras em linguagem natural;
- receber texto, áudio e imagem;
- importar extratos financeiros;
- visualizar dados em um dashboard;
- conversar com a IA sobre a vida financeira do household.

## Princípios

- PostgreSQL é a fonte da verdade financeira.
- A IA interpreta linguagem, seleciona tools e produz respostas.
- A IA não acessa diretamente o banco.
- Regras financeiras e cálculos permanecem determinísticos no backend.
- Toda operação sugerida por IA passa por validação de domínio.
- Dados enviados a providers externos devem ser minimizados.
- A arquitetura deve permitir substituir Groq por um provider self-hosted.

## Usuários

Estrutura inicial:

```text
Household
├── User A
│   └── WhatsappIdentity A
└── User B
    └── WhatsappIdentity B
```

Os dois usuários compartilham as mesmas informações financeiras, mas a autoria das operações é preservada.

## Stack

| Componente | Tecnologia |
|---|---|
| Backend | Kotlin + Ktor |
| JVM | JDK 21 |
| Build | Gradle Kotlin DSL |
| Banco | PostgreSQL |
| Migrations | Flyway |
| SQL | jOOQ |
| WhatsApp | Twilio |
| IA inicial | Groq |
| IA self-hosted futura | Ollama |
| Proxy/TLS | Caddy |
| Infra | Docker Compose |
| Dashboard | Next.js |

## Arquitetura

O projeto começa como um **modular monolith**.

```text
WhatsApp
   ↓
Twilio
   ↓
Sova API
   ↓
Application / Domain
   ↓
PostgreSQL
```

Uso da IA:

```text
Sova
  │
  │ HTTPS outbound
  ▼
Groq
  │
  │ tool_call
  ▼
Sova Tool Dispatcher
  │
  ▼
Domain
  │
  ▼
PostgreSQL
```

O Groq não acessa diretamente o Sova, PostgreSQL ou endpoints internos.

## Estrutura esperada do repositório

```text
sova/
├── AGENTS.md
├── README.md
├── docs/
│   ├── SPEC.md
│   ├── ARCHITECTURE.md
│   ├── ROADMAP.md
│   └── ADR/
│       ├── 0001-modular-monolith.md
│       ├── 0002-ai-local-tool-calling.md
│       └── 0003-household-model.md
├── backend/
├── frontend/
├── infra/
├── docker-compose.yml
└── .env.example
```

## Documentação

Antes de alterar comportamento ou arquitetura, consultar:

- `AGENTS.md`
- `docs/SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/ROADMAP.md`
- ADRs em `docs/ADR/`

## Roadmap resumido

1. Bootstrap
2. WhatsApp textual
3. Núcleo financeiro
4. Groq + local tool calling
5. Fluxo de caixa
6. Áudio
7. Imagem
8. Importação de extratos
9. Dashboard
10. IA self-hosted

Não avançar automaticamente entre fases.

## Ambiente inicial

Infraestrutura prevista:

```text
Hetzner CX23
2 vCPU
4 GB RAM
40 GB disk
x86
```

Containers iniciais:

```text
caddy
sova-api
postgres
```

A IA será inicialmente externa para preservar RAM e CPU da máquina.

## Configuração

Secrets não devem ser versionados.

Variáveis esperadas futuramente:

```text
DATABASE_URL
TWILIO_ACCOUNT_SID
TWILIO_AUTH_TOKEN
TWILIO_WHATSAPP_SENDER
GROQ_API_KEY
SOVA_AI_PROVIDER
```

O repositório deverá possuir apenas `.env.example`.

## Desenvolvimento

### Subir o ambiente completo

O ambiente local exige Docker com o plugin Compose. Os valores padrão são apenas para desenvolvimento e permitem iniciar a stack sem criar um `.env`:

```bash
docker compose up -d
```

Quando os serviços estiverem saudáveis, a API fica disponível por meio do Caddy:

```bash
curl http://localhost:8080/health
```

Resposta esperada:

```json
{"status":"UP","components":{"database":"UP"}}
```

Endpoints operacionais:

- `GET /health/live`: confirma que o processo da API está vivo;
- `GET /health/ready`: confirma que a API está pronta e que o PostgreSQL responde;
- `GET /health`: alias de readiness usado pelo smoke test.

Para encerrar a stack sem apagar os dados:

```bash
docker compose down
```

### Configuração

Copie `.env.example` para `.env` quando precisar alterar os padrões. Não versione `.env`. Em produção:

- use uma senha forte em `DATABASE_PASSWORD`;
- configure `SOVA_ADDRESS` com o domínio público;
- exponha `SOVA_HTTP_PORT=80` e `SOVA_HTTPS_PORT=443` para o Caddy emitir e renovar certificados HTTPS automaticamente.

### Backend sem Docker

Com JDK 21 e um PostgreSQL acessível, exporte `DATABASE_URL`, `DATABASE_USER` e `DATABASE_PASSWORD` e execute:

```bash
cd backend
./gradlew run
```

No Windows, use `gradlew.bat run`.

Testes e empacotamento:

```bash
cd backend
./gradlew test buildFatJar
```

O artefato executável é gerado em `backend/build/libs/sova-api.jar`.

### CI

O workflow em `.github/workflows/ci.yml` executa os testes com JDK 21, gera o fat JAR e sobe os três containers para validar o endpoint de saúde através do Caddy.

## Status

Fase 0 — Bootstrap implementada. As funcionalidades de WhatsApp e do domínio financeiro permanecem nas fases seguintes do roadmap.
