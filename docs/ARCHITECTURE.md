# Sova — Architecture Notes

## Contexto

O Sova será executado inicialmente em uma Hetzner CX23:

- 2 vCPU;
- 4 GB RAM;
- 40 GB armazenamento;
- x86.

A IA principal será externa inicialmente para preservar recursos locais.

## Stack proposta

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
| IA local futura | Ollama |
| Proxy/TLS | Caddy |
| Infra | Docker Compose |
| Dashboard | Next.js |

## Containers iniciais

```text
caddy
sova-api
postgres
```

O processamento assíncrono poderá inicialmente rodar no próprio `sova-api`.

Um `sova-worker` separado pode ser criado quando áudio, imagem e importações justificarem.

## Fluxo WhatsApp

```text
WhatsApp
   ↓
Twilio
   ↓
HTTPS
   ↓
Caddy
   ↓
Ktor
   ↓
validação de assinatura
   ↓
identificação do usuário
   ↓
persistência
   ↓
processamento
   ↓
Twilio REST API
   ↓
WhatsApp
```

## Fluxo IA

```text
Sova
  │
  │ HTTPS outbound
  ▼
AiProvider externo (Gemini)
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

O provider de IA não realiza conexões inbound com a KVM.

## Liquidação de fluxo de caixa

```text
CashFlowEntry + scheduledOn
  ↓
validação local da ocorrência
  ↓
Transaction (data real)
  +
CashFlowSettlement (vencimento, autor, instante)
```

A Transaction e o CashFlowSettlement são persistidos na mesma transação PostgreSQL. A constraint por `(household_id, cash_flow_entry_id, scheduled_on)` impede quitação duplicada. Projeções ignoram ocorrências liquidadas; o saldo considera a Transaction real.

Após a análise de imagens, comprovantes são vinculados ao settlement por `SettlementReceipt`. O binário fica fora do PostgreSQL atrás da port `MediaStorage`; o banco mantém metadados, SHA-256 e relações compostas por household. A recuperação utiliza URL temporária assinada e nova verificação de integridade.

Uma liquidação pode registrar valor realizado diferente do previsto. O estorno é lógico e atômico: `CashFlowSettlement` passa a `CANCELLED`, sua `Transaction` deixa de compor o saldo e a ocorrência volta a ser pendente. A previsão original e os comprovantes históricos não são apagados.

## Local Tool Calling

Exemplo:

Usuário:

> Quanto gastamos com restaurante este mês?

O provider retorna intenção equivalente a:

```json
{
  "name": "get_expenses",
  "arguments": {
    "category": "RESTAURANT",
    "period": "CURRENT_MONTH"
  }
}
```

A aplicação:

1. valida o nome da tool;
2. desserializa os argumentos;
3. injeta `householdId` internamente;
4. consulta o domínio;
5. obtém o resultado;
6. envia somente o resultado necessário ao LLM.

## Segurança das Tools

O LLM não pode definir:

- `userId`;
- `householdId`;
- conexão;
- SQL;
- credenciais;
- URL arbitrária.

Essas informações pertencem ao contexto interno da aplicação.

## Identidade

```text
WhatsappIdentity
        │
        ▼
       User
        │
        ▼
    Household
        │
        ▼
 Financial Data
```

Existirão inicialmente:

```text
Household: 1
Users: 2
WhatsappIdentities: 2
```

## Idempotência

Twilio `MessageSid` deve ser persistido com constraint única.

Uma entrega duplicada do mesmo webhook não pode:

- criar duas mensagens;
- criar duas despesas;
- gerar duas respostas financeiras.

## Processamento assíncrono

Mensagens textuais simples podem começar com processamento inline.

Áudio, imagem e documentos devem usar jobs.

Estratégia inicial:

```text
processing_jobs
```

com PostgreSQL e:

```sql
FOR UPDATE SKIP LOCKED
```

Não será introduzido Kafka/RabbitMQ no MVP.

## Observabilidade

MVP:

- logs estruturados;
- correlation id;
- MessageSid;
- tool name;
- duração;
- status;
- erros.

Não registrar:

- API keys;
- tokens;
- credenciais;
- mídia completa em log.

## Estratégia de IA

```kotlin
interface AiProvider {
    suspend fun complete(request: AiRequest): AiResponse
}
```

Providers:

```text
GeminiAiProvider
OllamaAiProvider
```

O domínio não depende de nenhum SDK específico de IA.


## Estrutura do repositório e módulos

O Sova será mantido inicialmente em um **monorepo**.

Estrutura física esperada:

```text
sova/
├── AGENTS.md
├── README.md
├── docs/
│   ├── SPEC.md
│   ├── ARCHITECTURE.md
│   ├── ROADMAP.md
│   └── ADR/
│
├── backend/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/
│
├── frontend/
│   ├── package.json
│   └── src/
│
├── infra/
│   ├── caddy/
│   ├── docker/
│   └── scripts/
│
├── docker-compose.yml
└── .env.example
```

### Backend

O diretório `backend/` será inicialmente **um único projeto/módulo Gradle Kotlin**.

Não serão criados, neste estágio, módulos Gradle separados para:

- domain;
- application;
- infrastructure;
- WhatsApp;
- IA;
- importação.

O termo **modular monolith** descreve inicialmente a separação lógica e arquitetural do código, não uma divisão física obrigatória em múltiplos artefatos Gradle.

A modularização interna deve ser feita por packages e boundaries explícitos.

Uma organização inicial aceitável é:

```text
backend/src/main/kotlin/com/sova/
├── household/
├── transaction/
├── cashflow/
├── conversation/
├── whatsapp/
├── ai/
├── statement/
└── shared/
```

Cada área funcional pode conter suas próprias responsabilidades internas, desde que as regras de dependência arquitetural sejam preservadas.

Exemplo conceitual:

```text
transaction/
├── domain/
├── application/
└── infrastructure/
```

Não é obrigatório reproduzir exatamente essa árvore para todos os módulos. A estrutura deve refletir complexidade real, evitando packages vazios ou abstrações sem uso.

### Dependências internas

As dependências devem apontar conceitualmente para o domínio:

```text
delivery
   ↓
application
   ↓
domain
   ↑
infrastructure implements ports
```

O domínio não pode depender de:

- Ktor;
- Twilio;
- Groq;
- Ollama;
- PostgreSQL;
- jOOQ;
- HTTP;
- detalhes específicos de serialização externa.

### Evolução para múltiplos módulos Gradle

A separação física do backend em múltiplos módulos Gradle poderá ser adotada futuramente caso exista benefício concreto.

Exemplos de sinais que podem justificar essa mudança:

- crescimento significativo do backend;
- necessidade de reforçar boundaries em compile time;
- builds excessivamente lentos;
- componentes com ciclo de vida independente;
- necessidade real de reutilização isolada;
- dificuldade recorrente de manter dependências arquiteturais apenas por convenção.

Uma possível evolução futura seria:

```text
backend/
├── app/
├── modules/
│   ├── finance/
│   ├── conversation/
│   ├── whatsapp/
│   └── statement-import/
└── shared/
```

Essa estrutura **não deve ser criada antecipadamente**.

### Frontend

O diretório `frontend/` será um projeto independente em Next.js/TypeScript.

Ele pertence ao mesmo repositório, mas não é um módulo Gradle.

O frontend deverá consumir APIs expostas pelo backend e não acessar PostgreSQL diretamente.

### Infra

O diretório `infra/` contém configuração operacional.

Exemplos:

```text
infra/
├── caddy/
├── docker/
└── scripts/
```

`infra/` não é um módulo compilável da aplicação.

Deve conter apenas arquivos relacionados a:

- proxy/reverse proxy;
- TLS;
- Docker;
- deployment;
- scripts operacionais;
- configuração de ambiente sem secrets.

### IntelliJ IDEA

O diretório raiz `sova/` pode ser aberto como projeto no IntelliJ IDEA.

A expectativa inicial é:

```text
Sova
├── backend        ← projeto Gradle/Kotlin
├── frontend       ← projeto Node/Next.js
├── infra          ← arquivos de infraestrutura
├── docs
├── docker-compose.yml
└── AGENTS.md
```

O `backend/` é o único módulo Gradle obrigatório na fase inicial.

### Regra arquitetural

Não transformar diretórios em módulos Gradle apenas para representar camadas arquiteturais.

A separação física deve existir somente quando trouxer isolamento, manutenção ou benefícios de build mensuráveis.

Até que isso aconteça, preferir:

> um backend Gradle simples, com modularidade lógica forte.


## Deployment inicial

```text
Internet
   │
   ▼
Caddy :443
   │
   ├── /api/*
   └── /webhooks/*
          │
          ▼
       sova-api
          │
          ▼
       postgres
```

Gemini é acessado exclusivamente por chamadas HTTPS outbound do `sova-api`.

## Decisões arquiteturais

### Escolha: Kotlin

Motivo:

- experiência existente;
- domínio financeiro combina com tipagem forte;
- boa modelagem de regras;
- coroutines para I/O;
- facilidade operacional em uma única aplicação.

### Escolha: modular monolith

Não utilizar microservices no MVP.

Motivo:

- dois usuários;
- uma única máquina;
- baixo volume;
- menor custo operacional;
- possibilidade de separar componentes posteriormente.

### Escolha: PostgreSQL como fila inicialmente

Motivo:

- reduz infraestrutura;
- entrega semântica suficiente;
- mantém jobs após restart;
- volume esperado baixo.

### Escolha histórica: Groq inicialmente

Motivo:

- não consome RAM da CX23;
- baixa latência;
- suporta tool calling;
- permite substituir provider posteriormente.

### Escolha atual: Gemini via Google Cloud

Motivo:

- evita as limitações operacionais encontradas no free tier do Groq;
- usa ADC com Service Account e IAM explícito;
- preserva o mesmo `AiProvider` e o local tool calling;
- atende texto, áudio e, na fase própria, imagens com um único provider operacional.

Detalhes: `docs/ADR/0006-gemini-como-provider-exclusivo.md`.

### Escolha: household compartilhado

Motivo:

Os dois usuários operam sobre a mesma vida financeira, mas a autoria das operações deve ser preservada.
