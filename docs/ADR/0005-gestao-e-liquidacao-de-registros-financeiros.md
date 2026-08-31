# ADR 0005 — Gestão e liquidação de registros financeiros

## Status

Accepted.

## Contexto

O ADR 0003 definiu `Transaction` como imutável durante a construção do núcleo financeiro e reservou atualização e exclusão para um contrato posterior. O uso real pelo WhatsApp passou a exigir correção de lançamentos, remoção de registros cadastrados por engano e quitação antecipada de ocorrências planejadas.

Uma `CashFlowEntry` mensal representa uma série. Portanto, marcar toda a entrada como concluída ao pagar apenas um mês encerraria incorretamente as ocorrências seguintes. Também é necessário evitar que uma conta paga continue reduzindo a projeção e, simultaneamente, criar o evento real que altera o saldo.

## Decisão

- transações e entradas futuras podem ser corrigidas por commands explícitos, sempre limitados ao household autenticado;
- exclusões são lógicas, com `deleted_at` e `deleted_by_user_id`, e registros excluídos deixam de participar de consultas, saldo e insights;
- alterações registram `updated_at` e `updated_by_user_id`, preservando a autoria original em `created_by_user_id`;
- uma transação criada por liquidação de fluxo de caixa não pode ser editada ou excluída isoladamente;
- cada quitação pertence à chave `(household_id, cash_flow_entry_id, scheduled_on)` e é idempotente por constraint única;
- quitar uma ocorrência cria atomicamente uma `Transaction` com origem `CASH_FLOW_SETTLEMENT` e um `CashFlowSettlement` que registra vencimento, autor e instante da liquidação;
- ocorrências liquidadas são excluídas das previsões futuras, enquanto a transação real passa a compor o saldo na data efetiva do pagamento ou recebimento;
- entradas futuras com liquidações não podem ser editadas ou excluídas, evitando invalidar o histórico já realizado;
- o valor realizado pode divergir do previsto e a variância é calculada deterministicamente sem alterar a previsão;
- o estorno marca o settlement como `CANCELLED`, exclui logicamente sua transação na mesma transação PostgreSQL e devolve a ocorrência à projeção;
- somente uma liquidação `ACTIVE` pode existir por ocorrência, preservando liquidações canceladas para auditoria;
- IDs de registros financeiros só podem ser reutilizados pela IA quando retornados por tools locais de consulta e continuam scoped pelo household no backend.

## Alternativas consideradas

### Exclusão física

Rejeitada porque removeria autoria e evidência de correções sobre dados financeiros.

### Desativar toda a recorrência ao pagar

Rejeitada porque uma quitação mensal não significa cancelar as próximas ocorrências da série.

### Alterar apenas o status da previsão

Rejeitada porque `CashFlowEntry` é planejamento; o saldo deve mudar por meio de uma `Transaction` real e rastreável.

### Anexar comprovante nesta fase

Adiada para a Fase 7.5. A liquidação criada agora será a âncora para o comprovante, depois que o pipeline seguro de imagens existir.

## Consequências

### Positivas

- erros podem ser corrigidos sem perder autoria;
- exclusões deixam de contaminar saldo e insights;
- recorrências são liquidadas por ocorrência;
- pagamentos antecipados afetam o saldo imediatamente e não são contados duas vezes na projeção;
- a futura mídia de comprovante terá um vínculo financeiro inequívoco.

### Negativas

- o modelo passa a ter estados de atualização, exclusão e liquidação;
- estornos aumentam o histórico persistido e exigem consultas que distingam liquidações ativas e canceladas;
- comprovantes vinculados permanecem como evidência histórica quando uma liquidação é cancelada.
