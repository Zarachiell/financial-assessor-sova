# Sova — Roadmap Técnico

## Estratégia

Ordem atual:

**bootstrap → WhatsApp textual → financial core → Groq/tool calling → cash flow → financial insights → áudio → imagem → importação → dashboard → self-hosted AI**

---

## Fase 0 — Bootstrap
Objetivo: projeto executável e implantável.

Entregáveis:
- Kotlin + Ktor
- JDK 21
- Gradle Kotlin DSL
- PostgreSQL
- Flyway
- jOOQ
- Docker Compose
- Caddy
- `.env.example`
- health check
- CI básica

Spec:
`docs/phases/00-bootstrap.md`

---

## Fase 1 — WhatsApp textual
Objetivo: receber e responder mensagens dos dois usuários autorizados.

Entregáveis:
- `POST /webhooks/twilio/whatsapp`
- validação `X-Twilio-Signature`
- idempotência por `MessageSid`
- persistência inbound
- 1 household compartilhado
- 2 users
- 2 WhatsappIdentities
- envio via Twilio
- tratamento básico de erros

Definição de pronto:
`ping -> pong` para Pai e Mãe.

Spec:
`docs/phases/01-whatsapp-text.md`

---

## Fase 2 — Financial Core
Objetivo: núcleo financeiro determinístico sem IA.

Entregáveis:
- Account
- Category
- Transaction
- BigDecimal
- `household_id`
- `created_by_user_id`
- migrations
- jOOQ
- saldo
- despesas
- receitas
- transações
- resumo por categoria
- testes

Spec:
`docs/phases/02-financial-core.md`

---

## Fase 3 — Groq + Local Tool Calling
Objetivo: transformar linguagem natural em comandos e consultas locais.

Entregáveis:
- `AiProvider`
- `GroqAiProvider`
- ConversationOrchestrator
- tool registry
- tool dispatcher
- local tool calling
- `create_transaction`
- `get_current_balance`
- `get_expenses`
- `get_income`
- `get_transactions`
- `get_category_summary`
- idempotência de escrita
- confirmação de ambiguidades
- prompts versionados

Prompts:

```text
backend/src/main/resources/prompts/
├── sova-system.md
├── financial-rules.md
└── tool-calling.md
```

Contexto runtime:
- Pai/Mãe
- data atual
- timezone
- moeda

Spec:
`docs/phases/03-groq-tool-calling.md`

---

## Fase 4 — Cash Flow
Objetivo: planejamento financeiro futuro.

Entregáveis:
- CashFlowEntry
- recorrência ONCE
- recorrência MONTHLY
- occurrence calculator
- receitas previstas
- despesas previstas
- projeção de saldo
- `create_cash_flow_entry`
- `get_cash_flow_projection`
- integração via WhatsApp textual

Spec:
`docs/phases/04-cash-flow.md`

---

## Fase 5 — Financial Insights
Objetivo: tornar o Sova um assessor financeiro baseado em dados reais.

Entregáveis:
- `get_financial_snapshot`
- `compare_periods`
- `get_expense_trend`
- `get_largest_expenses`
- `get_recurring_expenses`
- `get_budget_deviations`
- `get_financial_health_snapshot`
- taxa de poupança
- tendências
- comparação entre períodos
- gastos recorrentes
- desvios
- projeção simples de fechamento do mês
- recomendações fundamentadas

Casos:
- Onde estamos gastando demais?
- Onde podemos economizar este mês?
- O que aumentou nos últimos 3 meses?
- Estamos gastando muito com delivery?
- Quero economizar R$ 1.000 por mês. Onde cortar?
- Como você avalia nossa situação financeira?

Spec:
`docs/phases/05-financial-insights.md`

---

## Fase 6 — Áudio
Objetivo: permitir mensagens de voz.

Pipeline:

```text
WhatsApp
  ↓
Twilio
  ↓
download media
  ↓
Groq Whisper
  ↓
texto
  ↓
pipeline textual existente
```

Entregáveis:
- detecção de áudio
- Attachment metadata
- download seguro
- `SpeechToTextProvider`
- `GroqWhisperProvider`
- transcrição
- persistência da transcrição quando necessário
- tratamento assíncrono quando necessário
- integração com ConversationOrchestrator

Regra:
áudio é apenas uma nova forma de produzir texto.

---

## Fase 7 — Imagens
Objetivo: interpretar comprovantes e recibos.

Entregáveis:
- detecção de imagem
- download seguro
- abstraction de vision
- provider inicial
- merchant
- valor
- data
- categoria sugerida
- confidence
- confirmação em baixa confiança
- rastreabilidade

Regra:
imagem produz dados candidatos; o domínio valida e persiste.

---

## Fase 8 — Importação de extratos
Objetivo: importar dados em lote com revisão.

Prioridade:
1. OFX
2. CSV
3. PDF digital
4. PDF escaneado/imagem

Entregáveis:
- StatementImport
- StatementImportItem
- parser OFX
- parser CSV
- normalização
- deduplicação
- categorização
- staging
- revisão
- rastreabilidade

Regra:
nenhum item vira Transaction diretamente durante parsing.

---

## Fase 9 — Dashboard
Objetivo: interface web do household.

Primeira versão:
- autenticação
- visão geral
- saldo
- receitas x despesas
- categorias
- transações
- fluxo de caixa
- financial insights
- importações
- cadastro manual

Segunda versão:
- chat com Sova
- relatórios
- filtros avançados
- tendências

Frontend:
Next.js + TypeScript.

---

## Fase 10 — Self-hosted AI
Objetivo: aumentar privacidade e reduzir dependência externa.

Entregáveis:
- `OllamaAiProvider`
- provider selection
- Whisper local
- OCR/Vision local
- benchmark de qualidade
- benchmark de latência
- benchmark de RAM/CPU
- avaliação de upgrade da KVM

Critérios:
- português
- intent detection
- entity extraction
- tool selection
- respostas financeiras
- transcrição
- vision/OCR

---

## Backlog futuro
- cartões de crédito
- fechamento de fatura
- parcelamentos
- metas
- orçamento mensal formal
- alertas proativos
- patrimônio líquido
- investimentos
- Open Finance
- backup automatizado
- comparação anual
- múltiplas moedas
- contas pessoais vs compartilhadas

---

## Regra de execução

O `ROADMAP.md` define ordem e escopo macro.

A implementação de cada fase deve seguir o contrato detalhado em:

```text
docs/phases/
```

O Codex não deve avançar automaticamente para fases seguintes.
