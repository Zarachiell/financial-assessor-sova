# Sova — Roadmap Técnico

## Estratégia

O roadmap prioriza um núcleo financeiro confiável antes de adicionar multimodalidade e interface web.

A ordem é:

**texto → domínio → consultas → áudio → imagem → importação → dashboard**

---

## Fase 0 — Bootstrap

### Objetivo

Ter um projeto executável, versionado e implantável na KVM.

### Entregáveis

- [x] Repositório Git
- [x] Kotlin + Ktor
- [x] JDK 21
- [x] Gradle Kotlin DSL
- [x] Docker Compose
- [x] PostgreSQL
- [x] Flyway
- [x] Caddy
- [x] `.env.example`
- [x] health check
- [x] CI básica

### Definição de pronto

```bash
docker compose up -d
```

deve iniciar uma API e PostgreSQL funcionais.

---

## Fase 1 — WhatsApp textual

### Objetivo

Receber e responder mensagens dos dois usuários autorizados.

### Entregáveis

- [x] endpoint `POST /webhooks/twilio/whatsapp`
- [x] validação `X-Twilio-Signature`
- [x] idempotência por MessageSid
- [x] persistência de inbound messages
- [x] lookup por número do WhatsApp
- [x] normalização de celular brasileiro com ou sem o nono dígito
- [x] whitelist dos dois usuários
- [x] envio de resposta via Twilio
- [x] tratamento básico de erros

### Definição de pronto

Os dois números autorizados conseguem enviar:

> ping

e receber:

> pong

Números não autorizados não conseguem utilizar o bot.

---

## Fase 2 — Núcleo financeiro

### Objetivo

Persistir informações financeiras sem IA.

### Entregáveis

- [x] Household
- [x] User
- [x] WhatsappIdentity
- [x] Account
- [x] Category
- [x] Transaction
- [x] CashFlowEntry
- [x] repositories
- [x] services/handlers
- [x] migrations
- [x] testes de domínio

### Definição de pronto

É possível criar e consultar transações através de testes/API interna sem qualquer LLM.

---

## Fase 3 — Groq + Tool Calling

### Objetivo

Transformar linguagem natural em comandos de domínio.

### Entregáveis

- [x] interface `AiProvider`
- [x] `GroqAiProvider`
- [x] modelo de mensagens
- [x] modelo de tool definitions
- [x] tool registry
- [x] tool dispatcher
- [x] validação de argumentos
- [x] `create_transaction`
- [x] `get_expenses`
- [x] `get_income`
- [x] `get_transactions`
- [x] `get_current_balance`
- [x] `get_category_summary`
- [x] resposta natural final
- [x] logs de AI interactions sem secrets

### Casos obrigatórios

- [x] "Gastei 32 reais no almoço hoje"
- [x] "Recebi 500 reais"
- [x] "Quanto gastamos este mês?"
- [x] "Quanto gastamos com restaurante?"
- [x] "Qual nosso saldo?"

### Definição de pronto

WhatsApp:

> Gastei R$ 32 no almoço.

Sova:

> Despesa registrada: R$ 32,00 · Alimentação · hoje.

---

## Fase 4 — Fluxo de caixa

### Objetivo

Permitir planejamento financeiro futuro.

### Entregáveis

- [ ] recorrência
- [ ] receitas previstas
- [ ] despesas previstas
- [ ] projeção de saldo
- [ ] `create_cash_flow_entry`
- [ ] `get_cash_flow_projection`

### Casos obrigatórios

- [ ] "Todo dia 10 pago R$ 2.000 de aluguel"
- [ ] "Recebo R$ 8.000 todo dia 5"
- [ ] "Quanto teremos no dia 25?"

---

## Fase 5 — Áudio

### Objetivo

Permitir lançamento e consulta por mensagem de voz.

### Pipeline

```text
Twilio
  ↓
download media
  ↓
Groq Whisper
  ↓
texto
  ↓
mesmo pipeline da Fase 3
```

### Entregáveis

- [ ] armazenamento de metadata do attachment
- [ ] worker assíncrono
- [ ] download seguro da mídia
- [ ] transcrição
- [ ] persistência da transcrição
- [ ] processamento pelo AiProvider

---

## Fase 6 — Imagens

### Objetivo

Interpretar comprovantes e recibos.

### Entregáveis

- [ ] detecção de imagem
- [ ] download seguro
- [ ] vision provider
- [ ] extração estruturada
- [ ] merchant
- [ ] valor
- [ ] data
- [ ] categoria
- [ ] confirmação quando baixa confiança

---

## Fase 7 — Importação de extratos

### Prioridade

1. OFX
2. CSV
3. PDF digital
4. PDF escaneado / imagem

### Entregáveis

- [ ] StatementImport
- [ ] StatementImportItem
- [ ] OFX parser
- [ ] CSV parser
- [ ] normalização
- [ ] deduplicação
- [ ] categorização
- [ ] revisão antes de confirmação
- [ ] rastreabilidade

### Regra

Nenhum item importado deve ser criado diretamente como `Transaction` antes da etapa de validação/commit.

---

## Fase 8 — Dashboard

### Objetivo

Dar visualização web para o household.

### Primeira versão

- [ ] autenticação
- [ ] visão geral
- [ ] saldo
- [ ] receitas x despesas
- [ ] categorias
- [ ] transações
- [ ] fluxo de caixa
- [ ] importações
- [ ] cadastro manual

### Segunda versão

- [ ] chat com Sova
- [ ] relatórios
- [ ] filtros avançados

---

## Fase 9 — Self-hosted AI

### Objetivo

Reduzir dependência de IA externa.

### Entregáveis

- [ ] `OllamaAiProvider`
- [ ] feature flag/provider selection
- [ ] testes de compatibilidade de tools
- [ ] benchmark de qualidade
- [ ] benchmark de latência
- [ ] avaliação de upgrade da KVM

### Critério de troca

A troca só deve ocorrer quando o modelo local atingir qualidade aceitável para:

- intent detection;
- entity extraction;
- tool selection;
- português;
- respostas financeiras.

---

## Backlog futuro

- [ ] parcelamentos
- [ ] cartões de crédito
- [ ] fechamento de fatura
- [ ] metas
- [ ] orçamento mensal
- [ ] detecção de assinaturas
- [ ] despesas anômalas
- [ ] patrimônio líquido
- [ ] investimentos
- [ ] Open Finance
- [ ] backup automatizado
- [ ] alertas proativos
