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
- A arquitetura mantém ports internos que permitem uma troca futura de provider sem alterar o domínio; operacionalmente, o provider atual é apenas Gemini.

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
| IA | Gemini via Google Cloud |
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
AiProvider externo
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

O provider de IA não acessa diretamente o Sova, PostgreSQL ou endpoints internos.

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
5. Migração para Gemini via Google Cloud
6. Fluxo de caixa
7. Financial insights
8. Áudio
9. Imagem
10. Importação de extratos
11. Dashboard
12. IA self-hosted

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

Containers da aplicação:

```text
caddy
sova-api
sova-web
postgres
```

A IA será inicialmente externa para preservar RAM e CPU da máquina.

## Configuração

Secrets não devem ser versionados.

Variáveis utilizadas pelo backend nesta fase:

```text
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
TWILIO_ACCOUNT_SID
TWILIO_AUTH_TOKEN
TWILIO_WHATSAPP_SENDER
SOVA_HOUSEHOLD_NAME
SOVA_USER_A_NAME
SOVA_USER_A_WHATSAPP
SOVA_USER_B_NAME
SOVA_USER_B_WHATSAPP
AI_PROVIDER
GOOGLE_CLOUD_PROJECT
GOOGLE_CLOUD_LOCATION
GOOGLE_APPLICATION_CREDENTIALS
GEMINI_MODEL
GEMINI_AUDIO_MODEL
MAX_TOOL_ITERATIONS
SOVA_MAX_AUDIO_BYTES
SOVA_MAX_AUDIO_DURATION_MINUTES
SOVA_AUDIO_MAX_ATTEMPTS
SOVA_USER_A_DASHBOARD_PASSWORD
SOVA_USER_B_DASHBOARD_PASSWORD
SOVA_DASHBOARD_SESSION_KEY
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
- configure `SOVA_ADDRESS=http://2.28.2.205` para a VPS atual, que não utiliza domínio;
- substitua todas as configurações locais da Twilio e dos dois usuários;
- configure `AI_PROVIDER=gemini`, projeto/localização/modelos Google e ADC;
- exponha `SOVA_HTTP_PORT=80`; com `SOVA_ADDRESS=http://2.28.2.205`, o Caddy atende diretamente pelo IP sem tentar emitir certificado para um domínio inexistente.

### WhatsApp / Twilio

O startup provisiona um household e exatamente dois usuários autorizados a partir das variáveis `SOVA_HOUSEHOLD_NAME`, `SOVA_USER_A_*` e `SOVA_USER_B_*`. Números anteriormente provisionados que não fazem mais parte dessa configuração são desativados.

Quando ainda não existem dados financeiros ativos, o startup também cria a conta `Conta principal` e um catálogo básico de categorias de despesa e receita. Contas e categorias já existentes são preservadas.

Para identidades móveis brasileiras, o lookup trata os formatos com e sem o nono dígito como o mesmo usuário. O endereço bruto recebido da Twilio continua preservado nas mensagens para auditoria; a equivalência é usada somente na autorização do remetente.

Configure na Twilio o webhook:

```text
POST http://2.28.2.205/webhooks/twilio/whatsapp
```

O endpoint exige `application/x-www-form-urlencoded`, valida `X-Twilio-Signature` e aceita somente as duas identidades ativas. Os valores padrão do Compose são fictícios e existem apenas para que a stack local inicialize; eles não enviam mensagens reais.

Um sender da Twilio possui uma configuração principal de webhook inbound. Se o número já atende outro bot, não substitua essa URL diretamente: use um roteador compartilhado ou um sender separado para evitar desviar as mensagens da aplicação existente.

### Backend sem Docker

Com JDK 21 e um PostgreSQL acessível, exporte as variáveis de banco, Twilio, provisioning e do provider de IA listadas em `.env.example`. Depois execute:

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

### CI e deploy

O workflow em `.github/workflows/ci.yml` testa backend e frontend, gera os artefatos e sobe a stack completa para validar a API e o dashboard através do Caddy.

Depois de um CI bem-sucedido para um push na `main`, `.github/workflows/deploy.yml` envia exatamente o commit aprovado para a VPS e ativa uma nova release com Docker Compose. O bootstrap da VPS, as variáveis e os secrets necessários estão descritos em [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Status

Fase 8 — dashboard financeiro implementado. O Sova possui uma interface web autenticada e integralmente escura para visão patrimonial, dinheiro livre, investimentos, dívidas, previsto x realizado, timeline financeira, recorrências, liquidações e comprovantes. A API permite criar, editar e excluir transações e entradas de fluxo de caixa mantendo autoria e isolamento por household. O WhatsApp e o dashboard continuam usando os mesmos casos de uso e o PostgreSQL permanece como única fonte da verdade financeira.
