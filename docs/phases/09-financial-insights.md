# Fase 9 — Insights financeiros proativos e inteligência pessoal

## Objetivo

Transformar o Sova de um organizador reativo em um **assessor financeiro pessoal proativo**, capaz de detectar situações relevantes nos dados já existentes, explicar o motivo com números verificáveis e trazer recomendações úteis sem inventar fatos ou executar mutações financeiras sem autorização.

A inteligência desta fase deve ser construída sobre dados determinísticos produzidos pelo domínio.

A LLM interpreta e comunica.

O backend calcula e prova.

---

## Princípio central

```text
dados financeiros
  ↓
detectors / métricas determinísticas
  ↓
FinancialInsight
  ↓
priorização
  ↓
LLM explica em linguagem natural
  ↓
Pai / Mãe decide o que fazer
```

Nunca:

```text
LLM olha histórico bruto
  ↓
“acha” um padrão
  ↓
salva conclusão como verdade financeira
```

---

## Objetivos funcionais

O Sova deve conseguir identificar situações como:

- risco de saldo negativo futuro;
- concentração anormal de despesas;
- aumento de gasto por categoria;
- divergências relevantes entre previsto e realizado;
- recorrências que subiram de valor;
- despesas previstas vencidas e ainda pendentes;
- sobra de caixa acima do padrão;
- comprometimento elevado da renda;
- mudança relevante no padrão mensal;
- gastos extraordinários;
- contas próximas do vencimento;
- deterioração ou melhora do fluxo de caixa projetado.

Os insights precisam ser:

- explicáveis;
- rastreáveis;
- household-scoped;
- deduplicados;
- priorizados;
- temporalmente válidos;
- silenciosos quando não houver algo realmente útil.

---

## Escopo

### Inclui

1. motor determinístico de insights;
2. métricas financeiras;
3. detectores;
4. severidade/prioridade;
5. persistência ou snapshot quando fizer sentido;
6. deduplicação;
7. expiração;
8. explicabilidade;
9. tools de consulta;
10. resumo conversacional;
11. exposição no dashboard;
12. notificações somente através da infraestrutura/canal já disponível e com política explícita;
13. testes.

### Não inclui

- aconselhamento de investimento personalizado;
- compra/venda de ativos;
- recomendação de crédito específico;
- Open Finance;
- ingestão de extrato bancário de terceiros;
- alteração automática de orçamento;
- pagamento automático de contas;
- mutação financeira silenciosa;
- “IA autônoma” tomando decisões pelo household.

---

## Separação entre cálculo e linguagem

Toda conclusão numérica deve existir fora da LLM.

Exemplo correto:

```text
Detector:
- despesas de alimentação últimos 30 dias: R$ 1.840
- média dos 3 períodos anteriores comparáveis: R$ 1.310
- variação: +40,46%
- threshold: +25%
- insight: EXPENSE_CATEGORY_SPIKE
```

Depois:

```text
LLM:
“Os gastos com alimentação subiram cerca de 40% em relação à média recente...”
```

Exemplo incorreto:

```text
prompt com 500 transações
↓
“analise e veja se tem algo estranho”
```

A LLM pode contextualizar, mas não deve ser a calculadora ou detector primário.

---

## Modelo conceitual

Adaptar às convenções atuais do projeto.

```text
FinancialInsight
  id
  householdId
  type
  severity
  status
  titleKey / presentationHint?
  periodStart?
  periodEnd?
  detectedAt
  validUntil?
  fingerprint
  evidence
  metadata
  sourceVersion
  acknowledgedAt?
  dismissedAt?
```

Persistência é recomendada quando necessária para:

- deduplicação;
- histórico;
- dashboard;
- notificações;
- acknowledge/dismiss;
- auditoria.

Se parte dos insights puder ser calculada on-demand, manter a arquitetura simples, mas preservar fingerprint e comportamento idempotente onde houver notificação.

---

## Tipos iniciais de insight

Começar com um conjunto pequeno e de alto valor.

### 1. `PROJECTED_NEGATIVE_BALANCE`

Detectar quando o saldo projetado cruza abaixo de zero.

Evidência:

```text
currentBalance
projectedBalance
crossingDate
largestPendingExpenses
expectedIncomeBeforeCrossing
```

### 2. `UPCOMING_LARGE_EXPENSE`

Despesa relevante próxima do vencimento.

O threshold deve ser configurável ou derivado de métricas do household, não hardcoded em prompt.

### 3. `OVERDUE_PENDING_EXPENSE`

Ocorrência vencida sem settlement ativo.

### 4. `PLANNED_VS_ACTUAL_VARIANCE`

Diferença relevante entre valor previsto e liquidado.

Pode ser analisada por item e por categoria/período.

### 5. `CATEGORY_SPENDING_SPIKE`

Gasto realizado de uma categoria aumentou significativamente em comparação com baseline.

### 6. `RECURRING_EXPENSE_INCREASE`

Uma despesa recorrente passou a liquidar consistentemente acima do valor histórico ou previsto.

### 7. `MONTHLY_CASHFLOW_DETERIORATION`

Projeção do mês piorou de forma significativa em relação a uma referência anterior.

Não adicionar dezenas de detectores nesta primeira versão.

Priorizar precisão e utilidade.

---

## Evidência

Todo insight deve carregar evidência suficiente para ser explicado e testado.

Exemplo:

```json
{
  "type": "PROJECTED_NEGATIVE_BALANCE",
  "severity": "HIGH",
  "evidence": {
    "currentBalance": 6631.11,
    "projectedBalance": -368.89,
    "crossingDate": "2026-09-01",
    "drivers": [
      {
        "description": "Aluguel do AP novo",
        "amount": 3500.00
      },
      {
        "description": "Caução AP novo",
        "amount": 3500.00
      }
    ]
  }
}
```

A API/LLM não deve precisar reconsultar todo o histórico para justificar o insight.

---

## Severidade

Modelo simples:

```text
INFO
WARNING
HIGH
```

A severidade deve ser calculada por regra.

Não permitir que a LLM escolha severidade livremente.

Exemplos:

- conta vencida de baixo impacto: `INFO` ou `WARNING`;
- risco de saldo negativo iminente: `HIGH`;
- pequeno desvio de categoria: possivelmente nenhum insight.

---

## Thresholds

Thresholds devem ficar em configuração ou código de domínio/aplicação claramente testável.

Não colocar regra crítica apenas no system prompt.

Exemplos:

```text
category spike >= 25%
projected negative balance within N days
large expense >= X% of monthly income
variance >= max(absoluteThreshold, percentageThreshold)
```

Os valores exatos devem ser escolhidos depois de inspecionar os dados e a arquitetura atual.

Preferir thresholds conservadores inicialmente para evitar spam.

---

## Baselines

Comparações precisam ser semanticamente corretas.

Evitar comparar:

```text
primeiros 5 dias do mês atual
vs
mês anterior inteiro
```

Preferir:

```text
mesmo intervalo relativo
```

ou períodos fechados comparáveis.

Para categorias:

- usar settlements realizados, não apenas previsões;
- excluir cancelados;
- tratar categorias novas;
- tratar períodos sem dados;
- evitar divisão por zero.

---

## Idempotência e deduplicação

Criar `fingerprint` determinístico para insights persistidos/notificáveis.

Exemplo conceitual:

```text
hash(
  householdId,
  insightType,
  entity/category,
  referencePeriod,
  relevantVersion
)
```

Reexecutar o detector não deve gerar múltiplos insights equivalentes.

Um insight pode ser reaberto somente quando houver nova evidência material conforme regra explícita.

---

## Ciclo de vida

Status sugeridos:

```text
ACTIVE
ACKNOWLEDGED
DISMISSED
EXPIRED
RESOLVED
```

Usar somente os estados necessários.

Exemplos:

- risco de saldo negativo deixa de existir após nova receita → `RESOLVED`;
- conta vencida é liquidada → `RESOLVED`;
- usuário descarta um alerta de categoria → `DISMISSED`;
- insight antigo perde janela temporal → `EXPIRED`.

---

## Motor de detecção

Criar serviço de aplicação/domínio equivalente a:

```text
FinancialInsightEngine
```

Responsabilidades:

1. carregar read models necessários;
2. executar detectores independentes;
3. produzir candidates;
4. calcular fingerprint;
5. deduplicar;
6. persistir quando aplicável;
7. resolver/expirar insights antigos;
8. retornar insights priorizados.

Cada detector deve ser testável isoladamente.

Exemplo:

```text
ProjectedNegativeBalanceDetector
CategorySpendingSpikeDetector
OverduePendingExpenseDetector
PlannedVsActualVarianceDetector
```

Evitar um método gigante com dezenas de `if`.

---

## Execução

Suportar dois modos.

### On-demand

Quando Pai/Mãe perguntar:

- “Tem alguma coisa que eu deveria me preocupar?”
- “Como estão minhas finanças?”
- “O que mudou este mês?”
- “Tem alguma conta fora do normal?”

A tool executa/consulta o motor e retorna evidência estruturada.

### Proativa

Se a infraestrutura atual já suportar jobs/scheduler de forma adequada, executar detectores em frequência moderada.

Exemplo:

```text
1x por dia
```

ou após eventos financeiros relevantes.

Não criar polling agressivo.

Não adicionar Redis/queue apenas para esta fase sem necessidade concreta.

Se não houver scheduler, implementar primeiro o modo on-demand e uma interface de execução reutilizável para scheduler futuro.

---

## Recalcular após eventos

Determinados commands podem invalidar insights.

Exemplos:

```text
nova ocorrência
liquidação
cancelamento de settlement
alteração de recorrência
nova receita
```

Evitar acoplamento forte.

Preferir evento de aplicação/domínio ou invalidação/reprocessamento já compatível com a arquitetura existente.

---

## Tools da IA

Adicionar tools pequenas e orientadas a leitura.

### `get_financial_insights`

Filtros conceituais:

```text
period?
severity?
type?
status?
limit?
```

### `acknowledge_financial_insight`

Somente se a UX exigir marcar como visto.

### `dismiss_financial_insight`

Somente se a UX exigir descartar.

A LLM não deve fabricar novos registros de insight.

Ela só pode consultar ou alterar ciclo de vida através de commands explícitos.

---

## Resposta conversacional

A personalidade do Sova pode permanecer no canal de apresentação, mas a mensagem precisa manter precisão financeira.

Formato conceitual útil:

```text
achado
+
por que isso importa
+
números que sustentam
+
ação possível
```

Exemplo:

```text
Seu caixa projetado fica negativo em R$ 368,89 no dia 1º.
Os maiores impactos antes dessa data são o aluguel do AP novo e a caução, R$ 3.500 cada.
Se os dois realmente saírem nessa data, vale revisar o timing de uma dessas saídas ou confirmar se existe outra entrada prevista.
```

Não apresentar uma recomendação como obrigação.

---

## Recomendações

Podem existir recomendações operacionais de baixo risco, como:

- revisar uma despesa;
- confirmar data de vencimento;
- verificar se uma recorrência mudou;
- antecipar que o caixa ficará apertado;
- sugerir conferir uma categoria.

Não recomendar automaticamente:

- empréstimo;
- investimento específico;
- produto financeiro;
- movimentação bancária;
- cancelamento de despesa essencial;
- decisão fiscal/jurídica.

---

## Dashboard

Adicionar uma área de insights.

### Exibir

- severidade;
- título;
- explicação curta;
- evidência;
- período;
- status;
- data de detecção.

### Ações

Quando implementadas no backend:

- marcar como visto;
- dispensar;
- abrir detalhes relacionados;
- navegar para ocorrência/settlement/categoria.

O dashboard deve renderizar o insight do backend; não recalcular o detector no frontend.

---

## Privacidade e household isolation

Todo insight pertence a um household.

Os dados usados no cálculo precisam ser daquele household.

Testes obrigatórios:

```text
Household A financial data
↓
FinancialInsight A

Household B request
↓
não pode consultar, alterar, inferir ou receber evidence de A
```

Evidência também é dado financeiro e segue as mesmas regras de autorização.

---

## Observabilidade

Registrar:

- detector executado;
- duração;
- número de candidates;
- número de novos insights;
- deduplicados;
- resolvidos;
- falhas.

Evitar registrar evidência financeira completa em logs de produção.

Metrics agregadas são preferíveis.

---

## Performance

Detectores não devem varrer todo o histórico a cada request.

Usar:

- janelas temporais;
- queries agregadas;
- índices;
- read models;
- paginação/limites;
- processamento incremental quando realmente necessário.

Não otimizar prematuramente com infraestrutura nova.

---

## Testes

### Detectores

Cada detector deve possuir casos positivos, negativos e de fronteira.

Exemplos:

#### Saldo negativo

- cruza zero → gera;
- não cruza → não gera;
- cruza depois da janela → comportamento conforme regra;
- nova receita remove risco → resolve.

#### Categoria

- +40% com baseline válido → gera;
- +5% → não gera;
- categoria sem baseline → não gera falso positivo;
- período parcial → comparação correta.

#### Variância

- previsto 170, realizado 186,42;
- diferença irrelevante;
- settlement cancelado;
- ocorrência ainda pendente.

### Deduplicação

- mesma execução duas vezes → um insight;
- nova evidência material → atualização/reabertura conforme regra;
- execução concorrente → sem duplicidade.

### Autorização

- read cross-household;
- acknowledge cross-household;
- dismiss cross-household;
- evidence cross-household.

### Integração

```text
ocorrência prevista
↓
settlement com valor diferente
↓
detector de variance
↓
FinancialInsight
↓
tool
↓
resposta da IA
↓
dashboard
```

---

## Critérios de aceite

- [ ] existe motor determinístico de insights;
- [ ] nenhum detector crítico depende da LLM para fazer conta;
- [ ] conjunto inicial de detectores está implementado e testado;
- [ ] todo insight possui evidência rastreável;
- [ ] severidade é determinada por regra;
- [ ] deduplicação é idempotente;
- [ ] insights podem expirar/ser resolvidos;
- [ ] queries são household-scoped;
- [ ] cross-household é bloqueado;
- [ ] tool de insights retorna dados estruturados;
- [ ] a LLM explica, mas não inventa os números;
- [ ] dashboard exibe os insights;
- [ ] não há spam de alertas equivalentes;
- [ ] performance é aceitável para o volume atual;
- [ ] testes unitários e de integração passam;
- [ ] documentação de thresholds e detectores foi criada;
- [ ] nenhuma mutação financeira é executada automaticamente por um insight.

---

## Preparação para fases futuras

A arquitetura deve permitir futuramente, sem exigir implementação agora:

- metas financeiras;
- orçamento por categoria;
- notificações configuráveis;
- Open Finance;
- conciliação automática;
- comparação com extratos;
- análise de longo prazo;
- cenários “e se?”;
- relatórios periódicos.

Não antecipar esses módulos dentro da Fase 9.

---

## Instruções de implementação para o Codex

Antes de alterar código:

1. mapear read models financeiros existentes;
2. revisar cálculo de saldo atual/projetado;
3. revisar ocorrências, recorrências e `CashFlowSettlement`;
4. revisar API/dashboard da Fase 8;
5. localizar scheduler/jobs, caso existam;
6. localizar mecanismo de eventos internos, caso exista;
7. revisar autorização/household context;
8. revisar abstrações de tools e orchestrator.

Depois, apresentar um plano fechado antes de editar.

Durante a implementação:

- cálculos ficam em código testável;
- prompts não carregam regra financeira crítica;
- LLM não acessa repositório diretamente;
- tools chamam application layer;
- não criar infraestrutura distribuída sem necessidade;
- evitar detectores genéricos baseados apenas em prompt;
- thresholds devem ser configuráveis/testáveis;
- preservar Compose e deploy existentes;
- manter compatibilidade com WhatsApp e dashboard.

Ao final:

1. rodar toda a suíte;
2. listar detectores implementados;
3. listar thresholds iniciais;
4. demonstrar deduplicação;
5. demonstrar resolução de insight após mudança financeira;
6. demonstrar isolamento entre households;
7. demonstrar uma consulta por WhatsApp;
8. demonstrar o mesmo insight no dashboard;
9. listar arquivos/migrations alterados;
10. registrar débitos técnicos restantes.
