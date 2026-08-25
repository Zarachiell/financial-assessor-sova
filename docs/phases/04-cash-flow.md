# Phase 04 — Cash Flow

## Status

Implemented. Automated tests, fat JAR and local Docker upgrade from Flyway V4 to V5 complete.

## Objective
Adicionar planejamento financeiro futuro ao Sova.

Permitir cadastrar entradas e saídas esperadas, inclusive recorrentes mensalmente, e calcular projeção de caixa.

Exemplos:
```text
Todo dia 10 pago R$ 2.000 de aluguel.
Recebo R$ 8.000 todo dia 5.
Quanto teremos no dia 25?
```

## In scope
- `CashFlowEntry`;
- recorrência `ONCE` e `MONTHLY`;
- receitas/despesas previstas;
- data inicial/final;
- ativação/desativação;
- projeção;
- tools;
- WhatsApp textual;
- migrations;
- testes.

## Out of scope
- cron genérico;
- feriados bancários;
- cartão/fatura;
- parcelamento;
- Open Finance;
- geração automática de Transaction no vencimento;
- notificações proativas;
- insights avançados;
- áudio/imagem.

## Transaction vs CashFlowEntry
Transaction = algo que aconteceu.

CashFlowEntry = algo esperado/planejado.

Não misturar os conceitos.

## CashFlowEntry model
```text
id
household_id
created_by_user_id
account_id?
category_id?
type
amount
currency
description
recurrence
start_date
end_date?
day_of_month?
active
source
source_reference_id?
created_at
updated_at
```

Tipos:
```text
INCOME
EXPENSE
```

Recurrence:
```text
ONCE
MONTHLY
```

## Money rules
- BigDecimal;
- amount > 0;
- tipo define entrada/saída;
- default BRL.

## Monthly recurrence
`MONTHLY` usa `day_of_month = 1..31`.

Se o dia não existir no mês, usar o último dia.

```text
31 em fevereiro -> último dia
31 em abril -> 30
```

## Once
`ONCE` usa `start_date` como data prevista. `day_of_month` deve ser nulo.

## Active period
Considerar ocorrência somente quando:
```text
active = true
date >= start_date
date <= end_date, se existir
```

## Projection
`get_cash_flow_projection(targetDate)`:
```text
current balance
+ expected incomes
- expected expenses
= projected balance
```

Intervalo:
```text
today .. targetDate
```

## Projection result
```text
currentBalance
expectedIncome
expectedExpenses
projectedBalance
fromDate
targetDate
occurrenceCount
```

## Commands

### CreateCashFlowEntry
```text
type
amount
description
category?
account?
recurrence
startDate
endDate?
dayOfMonth?
```

Contexto:
```text
householdId
createdByUserId
source
sourceReferenceId
```

### DeactivateCashFlowEntry
Desativação lógica, sem apagar histórico.

## Queries
### GetCashFlowEntries
```text
type?
active?
from?
to?
```

### GetCashFlowProjection
```text
targetDate
accountId?
```

## AI tools
Adicionar:
```text
create_cash_flow_entry
get_cash_flow_projection
```

Opcional:
```text
get_cash_flow_entries
```

O LLM nunca fornece `householdId`/`userId`.

## Natural language examples

### Monthly expense
```text
Todo dia 10 pago R$ 2.000 de aluguel.
```

### Monthly income
```text
Recebo R$ 8.000 todo dia 5.
```

### One-time
```text
Vou pagar R$ 600 de seguro no dia 15 de setembro.
```

## Ambiguity
Mensagem incompleta como:
```text
Pago aluguel todo mês.
```

deve pedir dados faltantes. Não persistir entry incompleta.

## Persistence
Migration:
```text
cash_flow_entries
```

Constraints:
- amount > 0;
- recurrence válida;
- monthly exige day_of_month;
- once não exige day_of_month;
- end_date >= start_date;
- FKs.

## Occurrence generation
Não materializar todas as ocorrências futuras.

Calcular sob demanda:
```text
CashFlowEntry
 ↓
OccurrenceCalculator
 ↓
datas no intervalo
```

## Projection horizon
Limitar horizonte, sugestão:
```text
maxProjectionMonths = 24
```

Configurável.

## Tests
### Domain
- monthly válido;
- day inválido;
- once válido;
- end antes de start rejeitado;
- inativo ignorado.

### Occurrence calculator
- dia 10;
- dia 31 em fevereiro;
- leap year;
- start/end;
- target inclusive.

### Projection
- income;
- expense;
- mixed;
- current balance;
- household isolation;
- account filter.

### AI integration
Com fake provider:
```text
Todo dia 10 pago R$ 2.000 de aluguel
Quanto teremos no dia 25?
```

## Acceptance Criteria
### AC-04-01
É possível criar despesa mensal.

**PASS** — `create_cash_flow_entry` persiste despesas `MONTHLY` com dia, período, origem e autoria.

### AC-04-02
É possível criar receita mensal.

**PASS** — receitas `MONTHLY` usam o mesmo fluxo local e preservam o usuário autor dentro do household.

### AC-04-03
É possível criar entry `ONCE`.

**PASS** — `ONCE` usa `start_date` como única ocorrência e não aceita `day_of_month`.

### AC-04-04
`MONTHLY` exige `day_of_month` válido.

**PASS** — domínio, command e constraint PostgreSQL exigem valor entre 1 e 31.

### AC-04-05
Dia 31 em mês curto usa último dia.

**PASS** — o calculator usa o último dia do mês, incluindo 28/29 de fevereiro e 30 de abril.

### AC-04-06
Entries inativas não entram na projeção.

**PASS** — desativação é lógica e entradas inativas são excluídas antes do cálculo.

### AC-04-07
Projeção usa saldo atual + entradas - saídas previstas.

**PASS** — `CashFlowProjectionCalculator` calcula deterministicamente todos os totais no backend.

### AC-04-08
Projeção é scoped pelo household.

**PASS** — queries recebem o household autenticado e testes com dois households comprovam isolamento.

### AC-04-09
Projected balance é calculado pelo backend, não pelo LLM.

**PASS** — a tool recebe apenas a data alvo e devolve o resultado calculado pelo application service.

### AC-04-10
LLM não fornece user/household.

**PASS** — schemas não expõem IDs; `ToolExecutionContext` injeta identidade e autoria internamente.

### AC-04-11
Ocorrências futuras não são materializadas desnecessariamente.

**PASS** — `CashFlowOccurrenceCalculator` gera ocorrências em memória somente para o intervalo consultado.

### AC-04-12
WhatsApp textual suporta criação e consulta de fluxo.

**PASS** — integração com provider fake e PostgreSQL cobre aluguel, salário e projeção pelo fluxo WhatsApp completo.

## Verification

- `./gradlew test buildFatJar`: 65 testes, sem falhas ou skips;
- integração PostgreSQL valida idempotência, filtros, desativação, isolamento e constraints da V5;
- integração WhatsApp usa provider fake e executa as duas tools localmente, sem chamadas reais ao Groq ou à Twilio;
- Docker Compose atualizou um volume existente de V4 para V5 e manteve `caddy`, `sova-api` e `postgres` saudáveis;
- `/health` respondeu `200` por meio do Caddy após o upgrade.

## Definition of Done
CashFlowEntry persistida, ONCE/MONTHLY funcionando, occurrence calculator testado, projeção determinística, tools integradas, household/autoria preservados e testes verdes.

**Resultado:** atendido em 2026-08-25 sem materialização de ocorrências e sem avanço para áudio, imagem, dashboard ou insights avançados.

## Codex instruction
> Implemente `docs/phases/04-cash-flow.md`. Respeite `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md` e ADRs. Preserve a distinção entre Transaction e CashFlowEntry. Implemente apenas ONCE e MONTHLY. Toda projeção deve ser calculada no backend, nunca pelo LLM. Não implemente insights avançados, áudio, imagem ou dashboard. Ao finalizar, reporte cada AC como PASS ou FAIL, migrations e testes.
