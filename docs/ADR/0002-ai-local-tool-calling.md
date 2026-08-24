# ADR 0002 — IA com Local Tool Calling

## Status

Accepted

## Contexto

O Sova deve aceitar linguagem natural para:

- criar transações;
- criar fluxo de caixa;
- consultar saldo;
- consultar gastos;
- consultar receitas;
- gerar projeções;
- explicar informações financeiras.

O sistema utilizará inicialmente Groq como provider externo de IA.

É necessário preservar:

- segurança;
- privacidade;
- previsibilidade;
- capacidade futura de trocar o provider;
- regras financeiras determinísticas.

## Decisão

O Sova utilizará **local tool calling**.

A IA poderá selecionar uma tool e fornecer argumentos, mas a execução ocorrerá dentro do Sova.

Fluxo:

```text
Sova -> Groq
Groq -> tool_call
Sova -> valida tool
Sova -> executa domínio localmente
Sova -> Groq com resultado mínimo
Groq -> resposta natural
```

O Groq não acessará diretamente:

- PostgreSQL;
- endpoints internos;
- tools via rede;
- secrets;
- infraestrutura da KVM.

## Tool examples

Leitura:

```text
get_current_balance
get_expenses
get_income
get_transactions
get_category_summary
get_cash_flow_projection
```

Escrita:

```text
create_transaction
create_cash_flow_entry
```

## Regras

A IA nunca pode fornecer ou escolher:

- `userId`;
- `householdId`;
- SQL;
- credentials;
- URLs arbitrárias.

Esses valores devem ser derivados do contexto autenticado e do domínio.

Não criar tools genéricas como:

```text
execute_sql
run_query
http_request
execute_code
```

## Provider abstraction

A aplicação deve depender de um contrato interno:

```kotlin
interface AiProvider {
    suspend fun complete(request: AiRequest): AiResponse
}
```

Implementações planejadas:

```text
GroqAiProvider
OllamaAiProvider
```

Modelos e payloads específicos do Groq não devem vazar para domain/application.

## Alternativas consideradas

### IA acessando diretamente o banco

Rejeitado.

Motivos:

- risco elevado;
- ausência de controle determinístico;
- possibilidade de SQL incorreto;
- acoplamento entre LLM e schema;
- maior exposição de dados.

### Remote MCP

Rejeitado inicialmente.

Motivos:

- exige expor tools via rede;
- adiciona complexidade;
- não é necessário para um projeto pessoal;
- aumenta superfície de ataque.

### Enviar histórico completo ao LLM

Rejeitado.

O backend deve calcular localmente agregações e enviar somente o necessário.

## Consequências

### Positivas

- controle total de autorização;
- regras financeiras continuam locais;
- menor exposição de dados;
- provider substituível;
- fácil auditoria;
- tools testáveis sem IA real.

### Negativas

- algumas interações exigem duas ou mais chamadas ao provider;
- o Sova precisa de um tool dispatcher robusto;
- schemas de tools precisam de manutenção.

## Privacidade

Princípio:

> enviar ao provider apenas a informação mínima necessária para a tarefa atual.

Exemplo:

Em vez de enviar todas as transações de restaurante, calcular localmente:

```json
{
  "total": 872.30,
  "count": 14
}
```

e enviar apenas esse resultado ao LLM.
