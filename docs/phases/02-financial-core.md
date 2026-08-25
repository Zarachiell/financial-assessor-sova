# Phase 02 — Financial Core

## Status

Implemented. Automated tests, fat JAR and local Docker validation complete.

## Objective

Persistir e consultar o núcleo financeiro do household sem IA e sem depender do canal WhatsApp.

Ao final desta fase, uma API de aplicação Kotlin deve permitir criar contas, categorias, transações e lançamentos pontuais de fluxo de caixa, além de consultar as transações e os lançamentos criados.

## In scope

- reutilizar o `Household`, `User` e `WhatsappIdentity` existentes;
- `Account`;
- `Category`;
- `Transaction`;
- base de `CashFlowEntry` para lançamentos pontuais;
- dinheiro com `BigDecimal` e moeda `BRL` centralizada;
- autoria por `created_by_user_id`;
- isolamento por `household_id`;
- data civil financeira separada do timestamp técnico;
- rastreabilidade de origem;
- ports e application service/handlers internos;
- persistência PostgreSQL com jOOQ;
- migration Flyway;
- testes unitários, de application e de integração PostgreSQL.

## Out of scope

- Groq, Ollama ou qualquer LLM;
- tool calling;
- interpretar linguagem natural;
- novos comandos financeiros pelo WhatsApp;
- endpoints HTTP financeiros;
- dashboard ou autenticação web;
- recorrência diária, semanal, mensal ou anual;
- projeção de fluxo de caixa;
- cálculo de saldo e agregações financeiras;
- edição ou exclusão de lançamentos;
- parcelamentos, faturas e regras especiais de cartão;
- múltiplas moedas ou conversão cambial;
- importação de extratos.

Recorrência e projeção pertencem à Fase 4. Nesta fase, `CashFlowEntry` representa somente uma entrada ou saída futura pontual (`ONCE`).

## Existing identity model

Esta fase não cria um segundo modelo de identidade. Toda operação recebe um contexto autenticado interno:

```text
FinancialContext
  householdId
  userId
```

O contexto será fornecido futuramente pelo canal autenticado. `householdId` e `userId` nunca serão aceitos de argumentos produzidos por IA.

Antes de escrever ou consultar dados financeiros, a application layer deve confirmar que o usuário está ativo e pertence ao household informado.

## Money

Dinheiro deve utilizar um value object baseado em `BigDecimal`.

Regras da Fase 2:

- moeda única: `BRL`;
- escala: exatamente 2 casas após normalização;
- arredondamento silencioso não é permitido;
- valor deve ser maior que zero;
- persistência em `NUMERIC(19, 2)`;
- `Double` e `Float` são proibidos no domínio financeiro.

Os sinais não são armazenados no valor. A direção é representada por:

```text
INCOME
EXPENSE
```

## Dates and time

- `occurred_on`, `start_date` e `end_date` são datas civis (`DATE` / `LocalDate`);
- `created_at` e `updated_at` são timestamps técnicos (`TIMESTAMPTZ` / `Instant`);
- timestamps técnicos são gerados em UTC;
- nenhuma data ambígua é interpretada nesta fase.

## Domain model

### Account

```text
id
household_id
created_by_user_id
name
type
currency
active
created_at
updated_at
```

Tipos:

```text
CHECKING
SAVINGS
CASH
CREDIT_CARD
INVESTMENT
OTHER
```

Não existem regras de fatura ou limite para `CREDIT_CARD` nesta fase.

### Category

```text
id
household_id
created_by_user_id
name
type
active
created_at
updated_at
```

O tipo da categoria é `INCOME` ou `EXPENSE`. Uma categoria só pode ser usada por um lançamento do mesmo tipo.

### Transaction

```text
id
household_id
created_by_user_id
account_id
type
amount
currency
description
category_id
occurred_on
source
source_reference_id
created_at
```

Uma `Transaction` representa um evento financeiro já ocorrido. Ela é imutável nesta fase.

Origens suportadas:

```text
WHATSAPP_TEXT
WHATSAPP_AUDIO
WHATSAPP_IMAGE
DASHBOARD
STATEMENT_IMPORT
```

As origens de fases futuras existem no vocabulário do domínio, mas não criam integrações antecipadas.

### CashFlowEntry

```text
id
household_id
created_by_user_id
type
amount
currency
description
category_id
recurrence
start_date
end_date
active
source
source_reference_id
created_at
updated_at
```

Nesta fase:

```text
recurrence = ONCE
end_date = null
```

Outros tipos de recorrência e projeções serão adicionados apenas na Fase 4.

## Domain rules

- nomes de conta e categoria devem ser não vazios e ter no máximo 100 caracteres;
- descrições devem ser não vazias e ter no máximo 500 caracteres;
- conta, categoria, autor e lançamento devem pertencer ao mesmo household;
- conta e categoria devem estar ativas no momento da criação do lançamento;
- o tipo da categoria deve ser igual ao tipo da transação ou fluxo de caixa;
- a moeda da transação deve ser igual à moeda da conta;
- nomes de conta são únicos por household, ignorando caixa;
- nomes de categoria são únicos por household e tipo, ignorando caixa;
- `source_reference_id`, quando informado, deve ser não vazio e ter no máximo 200 caracteres;
- a mesma combinação `household_id + source + source_reference_id` não pode criar duas transações;
- a mesma regra de rastreabilidade vale separadamente para `CashFlowEntry`;
- nenhum registro financeiro pode existir sem autoria e household.

As invariantes importantes devem existir tanto no domínio/application quanto em constraints do PostgreSQL quando forem estruturalmente representáveis.

## Internal application API

Não criar endpoints HTTP nesta fase. A capacidade será exposta por um application service ou handlers Kotlin com operações equivalentes a:

```text
createAccount
listAccounts
createCategory
listCategories
createTransaction
listTransactions
createCashFlowEntry
listCashFlowEntries
```

Consultas são sempre filtradas pelo `householdId` do contexto autenticado. A consulta de transações deve aceitar filtros opcionais por período civil, tipo, conta e categoria e retornar ordenação determinística por data financeira e criação.

## Persistence

Criar uma nova migration, sem editar `V1`, `V2` ou `V3`.

Tabelas novas:

```text
accounts
categories
transactions
cash_flow_entries
```

Requisitos:

- UUID como PK;
- FKs para household, user, account e category;
- constraints compostas que impeçam referências entre households;
- checks para enums, dinheiro, nomes, descrições, datas e recorrência;
- índices para consultas por household e data;
- índices únicos parciais para idempotência por origem;
- queries explícitas com jOOQ;
- nenhuma dependência de ORM adicional.

## Tests

### Domain

- dinheiro aceita valores positivos com até duas casas e normaliza para escala 2;
- dinheiro rejeita zero, negativos, precisão excessiva e frações além de centavos;
- nomes, descrições e referências inválidas são rejeitados;
- modelos preservam `householdId`, autoria, moeda, tipo, datas e origem.

### Application

- usuário ativo do household cria conta e categoria;
- usuário desconhecido, inativo ou de outro household é rejeitado;
- transação válida é criada;
- conta/categoria inexistente, inativa ou incompatível é rejeitada;
- listagem nunca retorna dados de outro household;
- filtros de transação são aplicados;
- lançamento pontual de fluxo de caixa é criado sem implementar projeção.

### PostgreSQL integration

- migration aplica sobre banco vazio e sobre schema existente até V3;
- constraints de household, autoria, tipo, moeda e valor são efetivas;
- idempotência por origem é garantida no banco;
- criação e consulta preservam todos os campos;
- WhatsApp e health checks existentes continuam operacionais.

## Acceptance Criteria

### AC-02-01
O arquivo desta fase possui contrato detalhado e não é mais um placeholder.

**PASS** — este documento define escopo, modelo, regras, persistência, testes e critérios verificáveis.

### AC-02-02
Todo valor monetário do domínio financeiro usa `BigDecimal`, moeda `BRL`, escala 2 e rejeita arredondamento silencioso.

**PASS** — o value object `Money` usa `RoundingMode.UNNECESSARY`, valida precisão 19 e possui testes para limites e valores inválidos.

### AC-02-03
A migration da Fase 2 aplica sem alterar migrations existentes e cria `accounts`, `categories`, `transactions` e `cash_flow_entries` com integridade estrutural.

**PASS** — `V4__financial_core.sql` aplica sobre banco vazio e sobre V3; a atualização local real de V3 para V4 também foi validada.

### AC-02-04
Contas e categorias podem ser criadas e listadas pela API de aplicação com household e autoria preservados.

**PASS** — coberto por testes de application e persistência PostgreSQL.

### AC-02-05
Uma transação válida pode ser criada sem LLM e persiste conta, categoria, tipo, dinheiro, descrição, data civil, origem, household e autor.

**PASS** — validado ponta a ponta entre `FinancialApplicationService` e `PostgresFinancialRepository`.

### AC-02-06
Transações podem ser consultadas por household com filtros opcionais de período, tipo, conta e categoria, sem vazamento entre households.

**PASS** — filtros e isolamento são cobertos por testes de application e integração.

### AC-02-07
Referências cruzadas entre households e autores não pertencentes ao household são rejeitadas pela aplicação e protegidas pelo banco.

**PASS** — o contexto exige membro ativo e foreign keys compostas rejeitam autor, conta e categoria de outro household.

### AC-02-08
Categoria incompatível com o tipo do lançamento, conta inativa, categoria inativa ou moeda incompatível impedem a criação.

**PASS** — a application layer valida referências ativas e compatibilidade; tipos e a única moeda suportada também são restringidos no PostgreSQL.

### AC-02-09
Origem e `source_reference_id` são preservados e a mesma referência não cria duas transações nem dois lançamentos de fluxo de caixa.

**PASS** — a aplicação retorna o registro existente e índices únicos parciais garantem idempotência concorrente no banco.

### AC-02-10
Um `CashFlowEntry` pontual pode ser criado e listado, sempre com `recurrence = ONCE` e sem mecanismo de projeção ou recorrência futura.

**PASS** — domínio, aplicação, migration e testes aceitam somente `ONCE` com `end_date = null`.

### AC-02-11
Data civil financeira e timestamp técnico permanecem campos distintos e tipados.

**PASS** — `LocalDate`/`DATE` e `Instant`/`TIMESTAMPTZ` são persistidos e verificados separadamente.

### AC-02-12
O núcleo financeiro não depende de Ktor, Twilio, Groq, Ollama, HTTP ou modelos de provider externo.

**PASS** — domain e application usam somente tipos internos e JDK; jOOQ/PostgreSQL permanecem no adapter de infrastructure.

### AC-02-13
Nenhum endpoint HTTP financeiro, fluxo de linguagem natural ou funcionalidade de fases posteriores é implementado.

**PASS** — nenhuma rota, integração de IA, interpretação textual, projeção ou recorrência futura foi adicionada.

### AC-02-14
Testes unitários, de application e de integração PostgreSQL cobrem as regras relevantes sem chamadas reais para Twilio ou IA.

**PASS** — a suíte total possui 36 testes, incluindo domínio financeiro, application service, migrations e constraints PostgreSQL.

### AC-02-15
Os testes e o fat JAR passam, a stack Docker continua saudável e o fluxo `ping -> pong` não regride.

**PASS** — `gradlew test`, `buildFatJar` e `docker compose up --detach --build --wait` passaram; health e Flyway V4 foram confirmados, e a regressão do webhook passou junto da validação real `ping -> pong` de User A.

## Definition of Done

Todos os ACs devem estar comprovados por código, migration, testes e validação do ambiente. A fase não está concluída apenas por compilar e não pode avançar para IA, tool calling ou recorrência.

**Resultado:** atendido em 2026-08-24 com 36 testes sem falhas, fat JAR, migration V4 sobre schema existente, stack Docker saudável e sem avanço para a Fase 3.

## Codex instruction

> Implemente `docs/phases/02-financial-core.md` depois que este contrato estiver detalhado. Respeite `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md` e ADRs aplicáveis. Não implemente IA, tool calling, endpoints financeiros HTTP, recorrência, projeção ou fases posteriores. Ao finalizar, reporte cada AC como PASS ou FAIL, migrations criadas, testes executados e divergências encontradas.
