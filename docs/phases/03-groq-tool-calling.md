# Phase 03 — Groq + Local Tool Calling

## Status
Implemented.

## Objective
Integrar o Sova ao Groq para interpretar mensagens textuais e convertê-las em chamadas explícitas às capacidades financeiras locais.

Ao final desta fase, os dois usuários devem conseguir cadastrar despesas/receitas e consultar saldo, gastos, receitas, transações e categorias pelo WhatsApp.

A IA nunca acessa PostgreSQL diretamente.

## In scope
- `AiProvider`;
- `GroqAiProvider`;
- HTTPS outbound;
- local tool calling;
- tool registry;
- tool dispatcher;
- schemas e validação;
- conversation orchestrator;
- integração WhatsApp -> IA -> tool -> domínio -> IA -> WhatsApp;
- confirmação textual básica;
- testes com fake provider.

## Out of scope
- Ollama ativo;
- Gemini;
- áudio/imagem;
- OCR;
- cash-flow recorrente;
- financial insights avançados;
- dashboard;
- RAG/vector database;
- Remote MCP;
- SQL gerado por LLM.

## Provider boundary
Contrato interno:
```kotlin
interface AiProvider {
    suspend fun complete(request: AiRequest): AiResponse
}
```

Modelos internos representam messages, tools, tool calls, tool results e resposta final.

Classes específicas do Groq não podem vazar para domain/application.

## Configuration
```text
GROQ_API_KEY
SOVA_AI_PROVIDER=groq
GROQ_MODEL
```

Modelo configurável. API key nunca em Git, logs ou testes.

## Local tool calling
```text
User
 ↓
Sova
 ↓
Groq + tool definitions
 ↓
tool_call
 ↓
Sova valida
 ↓
Sova executa localmente
 ↓
resultado mínimo
 ↓
Groq
 ↓
resposta natural
 ↓
WhatsApp
```

Groq não recebe endpoint de tools.

## Initial tools

### create_transaction
Permitido:
```text
type
amount
description
category
occurredOn
account?
```

Proibido:
```text
userId
householdId
createdByUserId
databaseId
SQL
```

Contexto injetado:
```text
householdId
createdByUserId
source = WHATSAPP_TEXT
sourceReferenceId = inbound MessageSid
```

### get_current_balance
```text
account?
```

### get_expenses
```text
from
to
category?
account?
```

### get_income
Mesma lógica relevante.

### get_transactions
```text
from?
to?
type?
category?
account?
limit?
```

### get_category_summary
```text
from
to
```

## Category and account resolution
O LLM fornece nomes, nunca IDs internos.

Regras:
- resolver apenas dentro do household;
- normalizar match;
- não aceitar ID arbitrário;
- fallback seguro ou confirmação em caso de ambiguidade.

## Dates
Fornecer contexto temporal para:
```text
hoje
ontem
este mês
mês passado
```

Timezone:
```text
America/Fortaleza
```

Datas finais são validadas pelo backend.

## Confirmation policy
Mensagens claras podem executar:
```text
Gastei 32 reais no almoço hoje.
Recebi 500 reais de reembolso.
```

Mensagens materialmente ambíguas devem pedir confirmação:
```text
Acho que gastei uns 200 ontem.
```

Implementar apenas estado conversacional mínimo nesta fase.

## Conversation orchestrator
Responsável por:
```text
inbound
 ↓
AI request
 ↓
0..N tool calls
 ↓
tool execution
 ↓
AI final response
```

Limitar rounds, por exemplo:
```text
maxToolRounds = 5
```

Tool desconhecida deve ser rejeitada.

## Tool registry
Registro explícito:
```text
CreateTransactionTool
GetCurrentBalanceTool
GetExpensesTool
GetIncomeTool
GetTransactionsTool
GetCategorySummaryTool
```

Não usar reflection para expor métodos automaticamente.

## Minimal data exposure
Enviar apenas o necessário ao Groq.

Exemplo:
```json
{
  "total": 3240.50,
  "currency": "BRL",
  "period": "2026-08"
}
```

## Errors
Tratar:
- timeout;
- 4xx/5xx;
- rate limit;
- resposta inválida;
- JSON inválido;
- tool desconhecida;
- argumentos inválidos;
- tool failure;
- max rounds.

Não expor internals ao usuário.

### Provider diagnostics

Antes de cada chamada, `GroqAiProvider` registra somente métricas estruturais do payload efetivamente serializado:

```text
provider=groq
model=openai/gpt-oss-20b
estimatedInputTokens=6421
systemPromptChars=8120
toolDefinitionsChars=14350
conversationMessages=8
```

`estimatedInputTokens` é uma estimativa heurística e reproduzível: bytes UTF-8 do JSON enviado divididos por 4,
com arredondamento para cima. Não substitui a contagem oficial do tokenizer do modelo.

Em respostas HTTP de erro, registrar:

- status HTTP, tipo e código retornados pelo provider;
- body do erro limitado a 8 KiB, com API key e credenciais Bearer redigidas;
- tamanho original e indicador de truncamento do body;
- `retry-after`;
- limites, valores restantes e resets de requests/tokens informados pelos headers `x-ratelimit-*`.

Não registrar o payload, system prompt, mensagens da conversa, definições das tools ou API key.

## Idempotency
`create_transaction` deve usar:
```text
source = WHATSAPP_TEXT
sourceReferenceId = MessageSid
```

Mesmo inbound não pode criar duas transações.

## Tests
- serialização Groq;
- conversão de tool_call;
- erros HTTP;
- métricas estruturais do request e diagnósticos seguros de rate limit;
- resposta sem tool;
- uma/múltiplas tools;
- tool desconhecida;
- max rounds;
- create expense/income;
- balance;
- despesas;
- contexto injeta household/user;
- fake `AiProvider`;
- nenhum teste normal chama Groq real.

## Mandatory scenarios
```text
Gastei 32 reais no almoço hoje
Recebi 500 reais de reembolso
Quanto gastamos este mês?
Qual nosso saldo?
```

Todos os cálculos e persistência são locais.

## Acceptance Criteria
### AC-03-01
`GroqAiProvider` implementa `AiProvider`.

### AC-03-02
Domain/application não dependem de classes Groq.

### AC-03-03
Groq é acessado apenas por HTTPS outbound.

### AC-03-04
Tool calls são executadas localmente.

### AC-03-05
LLM não fornece `userId`/`householdId`.

### AC-03-06
Mensagem clara de despesa cria uma única Transaction.

### AC-03-07
Mensagem clara de receita cria uma única Transaction.

### AC-03-08
Saldo é calculado localmente.

### AC-03-09
Despesas são consultadas localmente.

### AC-03-10
Mesmo MessageSid não duplica Transaction.

### AC-03-11
Tool desconhecida não é executada.

### AC-03-12
Falha do Groq não expõe internals.

### AC-03-13
Testes normais não chamam Groq real.

### AC-03-14
Nenhum MCP remoto é exposto.

## Definition of Done
Groq funciona como provider inicial, tools são locais, WhatsApp textual cria receitas/despesas e executa consultas, identidade é injetada internamente, idempotência funciona e testes passam com fake provider.

## Verification

- `./gradlew test buildFatJar`;
- Docker Compose com `caddy`, `sova-api` e `postgres` saudáveis;
- health check validado por meio do Caddy;
- cenários obrigatórios cobertos com `AiProvider` fake e PostgreSQL real embutido;
- nenhuma chamada real ao Groq ou à Twilio durante os testes automatizados.

## Codex instruction
> Implemente `docs/phases/03-groq-tool-calling.md`. Respeite `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md` e ADR 0002. Use Groq apenas como `AiProvider`; toda tool deve ser executada localmente. Não exponha banco, SQL, MCP ou endpoints internos ao provider. Não implemente áudio, imagem, cash-flow recorrente ou insights avançados. Ao finalizar, reporte cada AC como PASS ou FAIL, tools e testes executados.
