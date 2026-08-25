# Phase 05 — Financial Insights

## Status

Implemented locally. Automated backend, PostgreSQL, tool and orchestrator validation, fat JAR and Docker upgrade
from Flyway V5 to V6 complete; production WhatsApp sandbox validation remains pending.

### Implemented product parameters

- tendência `STABLE`: variação absoluta menor que `5%`;
- baseline de desvio: média dos 3 meses completos anteriores ao período atual;
- severidade: `LOW < 10%`, `MEDIUM 10%..25%`, `HIGH > 25%`;
- projeção mensal: ritmo diário, disponível a partir de 5 dias observados;
- maiores despesas: no máximo 20 itens por chamada;
- plano de economia: somente categorias `DISCRETIONARY`, limitado a 30% do gasto atual de cada categoria;
- categorias padrão fixas: `Moradia` e `Saúde`;
- categorias padrão discricionárias: `Restaurante`, `Lazer` e `Compras`;
- demais categorias começam como `UNCLASSIFIED`.

### Recurring-expense heuristic

A detecção agrupa despesas pela mesma categoria e por descrição normalizada (caixa, acentos, pontuação e
sequências numéricas). Um candidato precisa ter ao menos duas datas distintas, intervalos de 21 a 40 dias e
variação máxima de 20% no valor. Intervalos de 25 a 35 dias e variação de até 10% elevam a confiança:

- `HIGH`: ao menos 4 ocorrências, intervalos fortes e valor estável;
- `MEDIUM`: ao menos 3 ocorrências fortes e estáveis, ou 4 ocorrências com intervalo mensal aproximado;
- `LOW`: demais candidatos que ainda respeitam os limites mínimos.

O resultado sempre é apresentado como inferência, nunca como assinatura confirmada.

## 1. Objective

Transformar o Sova de um sistema de registro e consulta em um assessor financeiro capaz de identificar padrões, comparar períodos e sugerir oportunidades de economia com base em dados reais do household.

Ao final desta fase, os usuários devem conseguir fazer perguntas abertas como:

```text
Onde estamos gastando demais?
Onde podemos economizar este mês?
O que aumentou nos últimos 3 meses?
Estamos gastando muito com delivery?
Qual categoria mais cresceu este ano?
Como você avalia nossa situação financeira?
```

As métricas devem ser calculadas deterministicamente pelo backend.

A IA deve apenas:

- interpretar a pergunta;
- selecionar tools;
- combinar resultados;
- explicar tendências;
- sugerir ações fundamentadas nos dados.

---

## 2. Scope

### In scope

- financial snapshot;
- comparação entre períodos;
- tendência por categoria;
- maiores despesas;
- despesas recorrentes detectáveis por histórico;
- desvios relevantes;
- taxa de poupança;
- projeção simples de fechamento do mês;
- participação de despesas fixas e discricionárias quando classificáveis;
- geração de recomendações via IA com base em métricas locais;
- novas AI tools;
- testes unitários, integração e orchestrator.

### Out of scope

Não implementar:

- recomendações de investimento;
- compra/venda de ativos;
- aconselhamento tributário;
- score financeiro genérico sem base objetiva;
- machine learning customizado;
- vector database;
- RAG;
- Open Finance;
- alertas proativos;
- áudio/imagem;
- orçamento avançado com metas automáticas;
- detecção perfeita de recorrência por ML.

---

## 3. Core Principle

O Sova deve separar:

```text
FATOS / CÁLCULOS
        ↓
backend determinístico
```

de:

```text
INTERPRETAÇÃO / EXPLICAÇÃO
        ↓
IA
```

Exemplo correto:

```text
PostgreSQL/Application:
Restaurantes:
- mês atual: R$ 1.280,40
- mês anterior: R$ 790,10
- variação: +62,06%
```

Depois:

```text
IA:
"Restaurantes foi uma das categorias que mais cresceram..."
```

Exemplo proibido:

```text
LLM inventa:
"Vocês gastaram 25% mais com restaurantes"
```

sem que esse cálculo tenha vindo do backend.

---

## 4. New Application Queries

Criar capacidades internas equivalentes a:

```text
get_financial_snapshot
compare_periods
get_expense_trend
get_largest_expenses
get_recurring_expenses
get_budget_deviations
get_financial_health_snapshot
```

Os nomes públicos das AI tools podem seguir esses nomes, desde que o contrato seja estável.

---

## 5. Financial Snapshot

### get_financial_snapshot

Entrada:

```text
from
to
```

Retorno mínimo:

```text
totalIncome
totalExpenses
netCashFlow
transactionCount
topExpenseCategories
topIncomeCategories
largestExpenses
```

Opcionalmente:

```text
averageDailyExpense
activeCashFlowEntries
projectedEndOfPeriodBalance
```

Somente incluir dados que possam ser calculados de forma consistente.

---

## 6. Period Comparison

### compare_periods

Entrada:

```text
currentFrom
currentTo
comparisonFrom
comparisonTo
```

Retorno mínimo:

```text
currentIncome
comparisonIncome
incomeAbsoluteChange
incomePercentageChange

currentExpenses
comparisonExpenses
expenseAbsoluteChange
expensePercentageChange

categoryChanges[]
```

`categoryChanges`:

```text
category
currentTotal
comparisonTotal
absoluteChange
percentageChange
```

### Division by zero

Quando o período anterior for zero:

- não retornar `Infinity`;
- `percentageChange` deve ser `null` ou possuir semântica explícita;
- resposta natural pode dizer "não houve gasto nessa categoria no período anterior".

---

## 7. Expense Trend

### get_expense_trend

Entrada:

```text
from
to
grouping
category?
```

`grouping` inicialmente:

```text
MONTH
WEEK
```

Retorno:

```text
periods[
  {
    period,
    total
  }
]
```

O backend pode calcular também:

```text
absoluteChange
percentageChange
direction
```

Direction:

```text
UP
DOWN
STABLE
```

A regra de `STABLE` deve ser explícita.

Sugestão inicial:

```text
abs(percentageChange) < 5%
```

Esse threshold deve ser centralizado/configurável.

---

## 8. Largest Expenses

### get_largest_expenses

Entrada:

```text
from
to
limit
category?
```

Retorno mínimo:

```text
transactionId
occurredOn
description
category
amount
currency
```

Limite máximo inicial recomendado:

```text
20
```

A tool não deve permitir dump ilimitado de transações para o LLM.

---

## 9. Recurring Expenses

### get_recurring_expenses

Objetivo:

Identificar possíveis gastos recorrentes a partir do histórico de `Transaction`.

Nesta fase, usar heurística determinística.

Não implementar ML.

Sinais possíveis:

- descrição normalizada semelhante;
- mesma categoria;
- valor igual ou próximo;
- repetição em intervalos mensais aproximados.

Exemplos:

```text
Netflix
Spotify
Academia
Internet
Seguro
```

Retorno:

```text
description
category
averageAmount
occurrenceCount
estimatedFrequency
lastOccurrence
confidence
```

### Confidence

Pode utilizar enum:

```text
LOW
MEDIUM
HIGH
```

A heurística deve ser documentada e testada.

Não afirmar que algo é assinatura com certeza quando for apenas inferência.

A resposta deve utilizar linguagem como:

```text
"parece recorrente"
"possível assinatura"
```

quando aplicável.

---

## 10. Budget Deviations

### get_budget_deviations

Nesta fase, como ainda não existe um módulo formal de orçamento completo, interpretar "desvio" como mudança relevante em relação a um baseline histórico.

Baseline inicial sugerido:

```text
média dos últimos 3 meses completos
```

Comparar o período atual contra essa média.

Retorno:

```text
category
currentAmount
baselineAverage
absoluteDeviation
percentageDeviation
severity
```

Severity:

```text
LOW
MEDIUM
HIGH
```

Thresholds devem ser centralizados.

Exemplo inicial:

```text
LOW    < 10%
MEDIUM 10%..25%
HIGH   > 25%
```

Esses valores são parâmetros de produto e podem ser refinados depois.

---

## 11. Savings Rate

Calcular:

```text
savingsRate = (income - expenses) / income
```

Quando:

```text
income > 0
```

Se `income = 0`, retornar:

```text
savingsRate = null
```

Nunca dividir por zero.

A taxa pode ser negativa quando despesas > receitas.

---

## 12. Fixed vs Discretionary Expenses

Nesta fase, evitar inferência excessiva.

É aceitável adicionar classificação explícita à `Category`:

```text
FIXED
DISCRETIONARY
UNCLASSIFIED
```

ou atributo equivalente.

Não permitir que o LLM altere essa classificação silenciosamente.

Se a classificação ainda não existir, retornar apenas métricas que possam ser calculadas corretamente e não bloquear a fase.

---

## 13. Financial Health Snapshot

### get_financial_health_snapshot

Não criar um "score 0-100" arbitrário nesta fase.

Retornar fatos estruturados:

```text
period
averageMonthlyIncome
averageMonthlyExpenses
averageMonthlyNetCashFlow
savingsRate
fixedExpenseRatio?
discretionaryExpenseRatio?
expenseTrend
cashReserveMonths?
projectedMonthEndBalance?
```

### cashReserveMonths

Só calcular se existir um conceito confiável de saldo/reserva disponível.

Fórmula inicial possível:

```text
currentAvailableBalance / averageMonthlyExpenses
```

Se os dados forem insuficientes:

```text
cashReserveMonths = null
```

Não inventar.

---

## 14. Month-End Projection

É permitido estimar o gasto até o fim do mês com base no ritmo atual.

Modelo inicial determinístico:

```text
averageDailyExpenseSoFar
x
numberOfDaysInMonth
```

Retornar separadamente:

```text
actualExpensesSoFar
projectedExpenses
projectionMethod
daysObserved
daysInPeriod
```

### Minimum data

Evitar projeção com pouquíssimos dados.

Sugestão:

```text
minimumObservedDays = 5
```

Antes disso, retornar projeção como indisponível ou baixa confiança.

Se Cash Flow já estiver implementado, uma projeção mais completa pode combinar:

```text
observed expenses
+
remaining planned cash-flow entries
```

Sem duplicar eventos já realizados.

---

## 15. Recommendation Generation

A IA pode produzir recomendações apenas quando houver evidência objetiva.

Exemplo:

```text
Restaurantes
Current: R$ 1.280
Previous: R$ 790
Change: +R$ 490 / +62%
```

Recomendação válida:

```text
"Restaurantes aumentou significativamente em relação ao mês anterior.
Uma redução de 25% sobre o gasto atual representaria cerca de R$ 320."
```

### Savings scenarios

Quando o usuário perguntar:

```text
Se quisermos economizar R$ 1.000 por mês, onde você cortaria?
```

O backend deve fornecer candidatos objetivos:

```text
category
currentMonthlyAverage
discretionary?
recentIncrease
```

A IA pode propor uma distribuição de cortes.

Se a IA fizer cálculo aritmético para distribuir a meta, o resultado deve ser validado pelo backend ou recalculado localmente antes da resposta final.

Preferência:

criar cálculo determinístico para cenários de redução simples.

---

## 16. Recommendation Safety

O Sova pode dar sugestões de organização e redução de gastos.

Deve evitar apresentar como certeza recomendações que dependam de contexto não disponível.

Exemplo:

```text
"Cancele seu plano de saúde"
```

não deve ser sugerido apenas porque é uma despesa alta.

Preferir gastos discricionários e mudanças observáveis.

Recomendações devem distinguir:

- dado;
- inferência;
- sugestão.

---

## 17. AI Tools

Adicionar tools:

```text
get_financial_snapshot
compare_periods
get_expense_trend
get_largest_expenses
get_recurring_expenses
get_budget_deviations
get_financial_health_snapshot
```

Manter tools anteriores:

```text
get_current_balance
get_expenses
get_income
get_transactions
get_category_summary
get_cash_flow_projection
```

O orchestrator pode executar múltiplas tools para uma pergunta.

---

## 18. Multi-Tool Example

User:

```text
Onde podemos economizar este mês?
```

Possível fluxo:

```text
Groq
 ↓
compare_periods
 ↓
get_budget_deviations
 ↓
get_recurring_expenses
 ↓
get_largest_expenses
 ↓
Sova retorna resultados mínimos
 ↓
Groq explica e recomenda
```

Não exigir todas as tools para toda pergunta.

---

## 19. Household Semantics

Todas as métricas pertencem ao household compartilhado.

Perguntas como:

```text
Quanto estamos gastando?
Onde podemos economizar?
```

devem considerar ambos os usuários.

A autoria continua disponível para auditoria, mas não separar os dados por usuário sem solicitação explícita.

Se futuramente houver pergunta:

```text
Quanto eu cadastrei?
```

isso pode usar `created_by_user_id`, mas não é objetivo central desta fase.

---

## 20. Privacy

Continuar minimizando dados enviados ao Groq.

Preferir:

```json
{
  "category": "Restaurantes",
  "current": 1280.40,
  "previous": 790.10,
  "changePct": 62.06
}
```

em vez de enviar todas as transações da categoria.

Enviar lista de transações somente quando a pergunta realmente exigir detalhes.

---

## 21. Performance

Queries analíticas devem ser executadas no PostgreSQL quando apropriado.

Usar:

```text
SUM
AVG
COUNT
GROUP BY
date_trunc
window functions
```

quando fizer sentido.

Evitar:

- carregar meses de transações para memória;
- loops Kotlin para agregações que o PostgreSQL executa eficientemente;
- N+1 queries.

---

## 22. Tests

### Financial calculations

- savings rate positiva;
- savings rate negativa;
- income zero;
- period comparison;
- percentage change com baseline zero;
- category aggregation;
- month projection;
- minimum observed days;
- fixed/discretionary ratio.

### Trend

- crescimento;
- queda;
- estabilidade;
- apenas um período;
- ausência de dados.

### Recurring detection

- recorrência mensal clara;
- variação pequena de valor;
- descrição semelhante;
- falso positivo evitado;
- confidence LOW/MEDIUM/HIGH.

### Household isolation

Criar dados de households distintos e garantir isolamento em todas as queries.

### AI orchestration

Com fake provider:

```text
Onde podemos economizar este mês?
```

deve executar tools analíticas sem enviar transações completas desnecessariamente.

### No real provider

Testes normais não chamam Groq real.

---

## 23. Mandatory Scenarios

### Scenario A — Savings opportunity

User:

```text
Onde podemos economizar este mês?
```

Expected:

- comparação objetiva;
- categorias relevantes;
- recomendação baseada em números;
- nenhuma métrica inventada.

### Scenario B — Trend

User:

```text
O que aumentou nos últimos 3 meses?
```

Expected:

- trend calculada localmente;
- categorias ordenadas por mudança relevante.

### Scenario C — Specific category

User:

```text
Estamos gastando muito com delivery?
```

Expected:

- dados históricos;
- comparação;
- resposta contextualizada.

### Scenario D — Savings target

User:

```text
Quero economizar R$ 1.000 por mês. Onde você sugere cortar?
```

Expected:

- identificar candidatos;
- respeitar valores reais;
- soma aproximada das sugestões coerente com a meta;
- não propor cortes impossíveis maiores que o gasto disponível.

### Scenario E — Financial overview

User:

```text
Como você avalia nossa situação financeira?
```

Expected:

- facts from financial health snapshot;
- resposta sem score arbitrário;
- indicar limitações quando dados forem insuficientes.

---

## 24. Acceptance Criteria

### AC-05-01
`get_financial_snapshot` retorna métricas calculadas localmente.

### AC-05-02
`compare_periods` calcula variações absolutas e percentuais corretamente.

### AC-05-03
Divisão por zero nunca produz Infinity/NaN em respostas financeiras.

### AC-05-04
`get_expense_trend` funciona por mês e por semana.

### AC-05-05
`get_largest_expenses` possui limite máximo e não permite dump ilimitado.

### AC-05-06
`get_recurring_expenses` usa heurística determinística e informa confiança.

### AC-05-07
`get_budget_deviations` compara com baseline local, não com opinião do LLM.

### AC-05-08
Savings rate é calculada pelo backend.

### AC-05-09
Financial health snapshot não produz score arbitrário.

### AC-05-10
Todas as queries são scoped por household.

### AC-05-11
Pergunta aberta pode resultar em múltiplas tool calls.

### AC-05-12
Recomendações citam ou se baseiam em métricas retornadas pelas tools.

### AC-05-13
LLM não inventa valores financeiros utilizados como fatos.

### AC-05-14
Dados enviados ao Groq são minimizados.

### AC-05-15
Queries analíticas relevantes usam agregação PostgreSQL.

### AC-05-16
Testes normais não chamam Groq real.

---

## 25. Definition of Done

A Fase 05 está concluída quando:

- todas as queries analíticas previstas estão disponíveis;
- métricas são determinísticas;
- tendências e comparações estão testadas;
- recurring detection possui heurística explícita;
- savings rate e snapshot financeiro funcionam;
- o orchestrator consegue combinar múltiplas tools;
- perguntas abertas geram respostas fundamentadas;
- nenhuma recomendação financeira depende de números inventados pelo LLM;
- privacidade e household isolation são preservados;
- todos os testes passam.

---

## 26. Manual Sandbox Validation

Criar um dataset de teste com pelo menos:

- 3 meses de histórico;
- receitas;
- supermercado;
- restaurantes;
- delivery;
- transporte;
- assinaturas;
- despesas recorrentes;
- aumento relevante em uma categoria.

Executar pelo WhatsApp:

```text
Onde podemos economizar este mês?
```

```text
O que mais aumentou nos últimos 3 meses?
```

```text
Quais despesas parecem recorrentes?
```

```text
Quero economizar R$ 1.000 por mês. O que você sugere?
```

```text
Como você avalia nossa situação financeira?
```

Comparar todas as afirmações quantitativas da resposta com os valores calculados no backend.

---

## 27. Codex instruction

> Implemente `docs/phases/05-financial-insights.md`. Respeite `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md` e ADRs aplicáveis. Toda métrica financeira, tendência, comparação e projeção deve ser calculada deterministicamente no backend/PostgreSQL. O Groq pode interpretar e explicar os resultados, mas não deve inventar fatos financeiros. Minimize dados enviados ao provider. Não implemente áudio, imagem, dashboard, Open Finance, RAG ou recomendações de investimento. Ao finalizar, reporte cada AC como PASS ou FAIL, queries/tools adicionadas, heurísticas adotadas, testes executados e cenários que exigem validação manual.
