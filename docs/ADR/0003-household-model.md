# ADR 0003 — Modelo financeiro isolado por household

## Status

Accepted.

## Context

O Sova possui dois usuários autorizados no mesmo household. A Fase 2 introduz contas, categorias, transações e lançamentos de fluxo de caixa, todos com dados financeiros compartilhados pelo household e autoria individual.

O modelo precisa impedir referências entre households, preservar quem criou cada registro e representar dinheiro e datas sem ambiguidades. Ao mesmo tempo, a fase não deve antecipar recorrência, projeções, múltiplas moedas ou regras especiais de cartão.

## Decision

- `household_id` é a fronteira de propriedade e consulta de todo registro financeiro;
- `created_by_user_id` registra autoria, mas não altera a propriedade compartilhada pelo household;
- contexto autenticado interno fornece `householdId` e `userId`; comandos financeiros não aceitam esses identificadores como argumentos de negócio;
- foreign keys compostas reforçam no PostgreSQL que autor, conta, categoria e lançamento pertencem ao mesmo household;
- dinheiro usa `BigDecimal` no domínio e `NUMERIC(19, 2)` no banco, sempre positivo, com direção separada em `INCOME` ou `EXPENSE`;
- `BRL` é a única moeda da fase e permanece explícita e centralizada;
- datas financeiras são `LocalDate`/`DATE`, enquanto timestamps técnicos são `Instant`/`TIMESTAMPTZ` em UTC;
- transações são imutáveis nesta fase e possuem origem e referência externa opcional protegida por unicidade estrutural;
- `CashFlowEntry` suporta apenas recorrência `ONCE`; recorrência real e projeções permanecem na Fase 4.

## Alternatives considered

### Propriedade financeira por usuário

Rejeitada porque os dois usuários compartilham o mesmo patrimônio e precisam consultar os mesmos registros. O usuário é autor, não tenant.

### Valores assinados

Rejeitada porque combinar sinal e tipo permite estados contraditórios. Valores permanecem positivos e o tipo define a direção.

### `Double` ou `Float` para dinheiro

Rejeitada por erros de representação binária e por contrariar as regras do domínio financeiro.

### Usar apenas timestamps para datas financeiras

Rejeitada porque uma transação pertence a uma data civil no timezone do household, independentemente do instante técnico de criação.

### Implementar recorrência completa na Fase 2

Rejeitada porque recorrência e projeção pertencem à Fase 4 e exigem regras adicionais que não são necessárias para validar o núcleo financeiro.

## Consequences

### Positive

- isolamento por household é verificável na aplicação e no banco;
- autoria é preservada sem fragmentar os dados compartilhados;
- dinheiro e datas possuem semântica explícita;
- idempotência por origem pode ser garantida estruturalmente;
- futuras integrações reutilizam o mesmo núcleo sem acoplá-lo ao WhatsApp ou à IA.

### Negative

- constraints compostas exigem chaves únicas auxiliares e queries de persistência mais explícitas;
- múltiplas moedas exigirão uma decisão futura além do contrato desta fase;
- atualização e exclusão de transações precisarão de um contrato posterior, pois os registros são imutáveis agora.
