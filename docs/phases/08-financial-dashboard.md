# Fase 8 — Dashboard financeiro e API de leitura consolidada

## Objetivo

Criar a primeira interface web de consulta do Sova, oferecendo uma visão confiável de:

- saldo;
- receitas e despesas;
- fluxo de caixa futuro;
- previsto x realizado;
- contas pendentes e liquidadas;
- recorrências;
- categorias;
- comprovantes de liquidação quando disponíveis;
- histórico financeiro por período.

O dashboard deve consumir **casos de uso e contratos de aplicação do backend**, sem reproduzir regras financeiras no frontend.

Esta fase transforma os dados já estruturados nas fases anteriores em uma interface de acompanhamento, mas não cria um segundo domínio financeiro.

---

## Princípio central

```text
Domínio financeiro
  ↓
queries / read models
  ↓
API autenticada
  ↓
dashboard
```

O frontend apresenta dados.

O backend continua sendo a fonte de verdade.

---

## Dependências

A Fase 8 assume concluídas:

- fluxo de caixa planejado;
- recorrências;
- tool calling;
- liquidação via `CashFlowSettlement`;
- Fase 7.5 para visualização de comprovantes, caso já tenha sido executada.

O dashboard deve funcionar mesmo quando uma ocorrência não possuir comprovante.

---

## Escopo

### Backend

- endpoints/read models próprios para dashboard;
- agregações por período;
- previsto x realizado;
- pendências;
- settlements;
- recorrências;
- categorias;
- visão consolidada de saldo;
- paginação;
- filtros;
- autorização por household;
- contratos estáveis e documentados.

### Frontend

- shell autenticado;
- seleção/contexto de household quando aplicável;
- visão geral;
- fluxo de caixa;
- transações/ocorrências;
- settlements;
- recorrências;
- comprovantes;
- filtros por período;
- estados de loading, vazio e erro;
- responsividade.

### Fora do escopo

- reconstruir commands financeiros no browser;
- cálculo independente de saldo no frontend;
- Open Finance;
- conciliação bancária automática;
- BI complexo;
- edição em massa;
- gráficos puramente decorativos sem utilidade financeira;
- sistema novo de autenticação se o projeto já possuir um mecanismo adequado.

---

## Arquitetura

Antes de escolher stack ou criar novo app, inspecionar o repositório.

Reutilizar a stack existente quando houver frontend já iniciado.

Se ainda não houver frontend, criar o menor módulo web compatível com a infraestrutura do projeto.

Evitar introduzir:

- novo banco;
- BFF desnecessário;
- segundo backend;
- cache distribuído sem necessidade;
- microfrontend;
- state manager complexo sem justificativa.

---

## Read models

O dashboard não deve montar telas fazendo dezenas de requests granulares quando uma consulta consolidada resolver melhor.

Criar read models específicos.

### `FinancialOverview`

Estrutura conceitual:

```text
period
currentBalance
projectedBalance
income
expense
plannedIncome
plannedExpense
realizedIncome
realizedExpense
pendingIncome
pendingExpense
variance
```

### `CashFlowTimeline`

Cada item deve distinguir:

```text
occurrenceId
settlementId?
type
description
category
plannedAmount
settledAmount?
dueDate
settledAt?
status
source
hasReceipt
```

### `CategorySummary`

```text
category
planned
realized
variance
percentageOfExpenses
```

### `SettlementSummary`

```text
settlementId
occurrenceId
description
plannedAmount
settledAmount
difference
dueDate
settledAt
hasReceipt
```

Os nomes finais devem seguir os padrões atuais do código.

---

## Endpoints

Não fixar rotas antes de inspecionar o padrão atual, mas o backend deve oferecer operações equivalentes a:

```text
GET financial overview
GET cash-flow timeline
GET pending occurrences
GET settlements
GET recurring entries
GET category summary
GET settlement receipt metadata
GET/download authorized receipt
```

### Requisitos

- autenticação;
- household scope;
- paginação quando aplicável;
- filtros por data;
- timezone consistente;
- serialização monetária sem perda de precisão;
- contrato de erro consistente;
- testes de autorização.

---

## Períodos

Suportar pelo menos:

- mês atual;
- mês anterior;
- próximo mês;
- intervalo customizado.

Datas devem ser interpretadas no timezone do household/usuário conforme modelo atual.

Não usar timezone do browser como fonte implícita de verdade para regra financeira.

---

## Tela 1 — Visão geral

Exibir no mínimo:

### Saldo atual

Valor realizado até o instante de referência.

### Saldo projetado

Saldo considerando ocorrências futuras ainda pendentes.

### Receitas do período

Separar previsto e realizado.

### Despesas do período

Separar previsto e realizado.

### Próximos compromissos

Ocorrências pendentes ordenadas por vencimento.

### Divergências

Exibir diferenças relevantes entre previsto e realizado.

Evitar esconder variância dentro de um único total.

---

## Tela 2 — Fluxo de caixa

Timeline/lista cronológica com:

- data;
- descrição;
- tipo;
- categoria;
- previsto;
- realizado;
- status;
- origem;
- comprovante quando houver.

Filtros mínimos:

- período;
- receita/despesa;
- status;
- categoria;
- recorrente/não recorrente.

Estados sugeridos:

```text
PENDING
SETTLED
CANCELLED_SETTLEMENT / novamente pendente conforme read model
OVERDUE
```

O frontend não deve inventar status que não sejam derivados de regras do backend.

---

## Tela 3 — Recorrências

Permitir consultar:

- recorrências ativas;
- valor;
- periodicidade;
- próxima ocorrência;
- categoria;
- data de início;
- data final quando houver.

Se já existirem commands seguros para criar/editar/encerrar recorrência e o escopo atual permitir, a UI pode expô-los.

Caso contrário, manter esta tela inicialmente somente leitura.

Não adicionar mutação sem command/backend correspondente.

---

## Tela 4 — Liquidações

Lista de `CashFlowSettlement` com:

- ocorrência vinculada;
- previsto;
- realizado;
- diferença;
- vencimento;
- data de liquidação;
- autoria/origem quando apropriado;
- comprovantes.

Quando a Fase 7.5 estiver presente:

```text
settlement
  ↓
receipt metadata
  ↓
URL/download autorizado
```

O frontend nunca deve receber caminho interno bruto de storage.

---

## Comprovantes

Regras:

- acesso sempre autenticado;
- autorização pelo household;
- preferir URL temporária/stream autorizado conforme storage escolhido;
- não tornar bucket/container público;
- não expor chave física interna como contrato de UI;
- múltiplos comprovantes devem ser suportados;
- falha ao carregar comprovante não pode quebrar a tela inteira.

---

## Gráficos

Adicionar somente gráficos que respondam perguntas financeiras claras.

### Recomendados

1. receitas x despesas por período;
2. previsto x realizado;
3. despesas por categoria;
4. evolução de saldo.

Não calcular os números novamente no frontend quando o backend já possuir read model consolidado.

---

## API de leitura consolidada

Evitar N+1 entre:

```text
occurrence
settlement
category
receipt
recurrence
```

Revisar queries e índices com dados realistas.

Adicionar paginação em históricos extensos.

Nenhuma tela deve exigir carregar toda a vida financeira do household para exibir o mês atual.

---

## Performance

Definir metas pragmáticas.

Exemplo:

- overview: uma quantidade pequena e previsível de queries;
- listas: paginadas;
- filtros executados no backend;
- anexos carregados sob demanda;
- nenhuma mídia binária embutida no JSON principal;
- índices para household + datas/status.

Não introduzir cache antes de medir necessidade.

---

## Segurança

### Household isolation

Todo endpoint deve derivar ou validar household pelo contexto autenticado.

Não confiar em `householdId` arbitrário vindo do frontend sem autorização.

### Comprovantes

Proteger:

- metadata;
- download;
- preview;
- IDs;
- URLs temporárias.

### Logs

Não registrar:

- conteúdo completo de comprovantes;
- URLs assinadas;
- tokens;
- dados financeiros desnecessários.

### Frontend

Não persistir tokens sensíveis em local inseguro se a autenticação existente oferecer alternativa mais segura.

Seguir o mecanismo já usado pelo projeto.

---

## UX financeira

O dashboard deve deixar visualmente claro:

- o que é previsto;
- o que é realizado;
- o que está pendente;
- o que venceu;
- o que divergiu;
- o que possui comprovante.

Não usar uma única coluna “valor” quando isso esconder a distinção entre `plannedAmount` e `settledAmount`.

---

## Compatibilidade com WhatsApp

O dashboard não substitui o WhatsApp.

Os dois canais devem operar sobre os mesmos casos de uso.

Exemplo:

```text
WhatsApp
  ↓
SettleCashFlowOccurrence
  ↓
CashFlowSettlement
  ↓
Dashboard reflete o dado

Dashboard
  ↓
query/read model
  ↓
mesmo CashFlowSettlement
```

Nenhum canal pode manter estado financeiro independente.

---

## Testes

### Backend

- overview correto;
- filtros por período;
- previsto x realizado;
- pendências;
- settlements;
- categorias;
- paginação;
- timezone;
- autorização;
- household isolation;
- acesso a comprovante;
- tentativa de acesso a comprovante de outro household.

### Frontend

- loading;
- empty state;
- error state;
- troca de período;
- filtros;
- renderização de moeda;
- diferenciação previsto/realizado;
- lista de pendências;
- visualização de settlement;
- comprovante disponível;
- comprovante ausente;
- múltiplos comprovantes;
- API retornando 401/403/404.

### Integração

Cobrir pelo menos:

```text
criar ocorrência
↓
liquidar via backend/tool
↓
consultar dashboard
↓
valor realizado aparece corretamente
```

E, com Fase 7.5:

```text
liquidar
↓
vincular comprovante
↓
consultar settlement
↓
abrir comprovante autorizado
```

---

## Observabilidade

Usar a infraestrutura atual.

Adicionar logs/metrics apenas onde trouxerem diagnóstico real:

- erro de read model;
- latência de consultas consolidadas;
- falha de autorização;
- falha ao resolver mídia;
- endpoint com volume elevado.

Evitar logar dados financeiros em nível excessivo.

---

## Critérios de aceite

- [x] existe interface web funcional e autenticada;
- [x] overview usa dados do backend;
- [x] previsto e realizado são distintos;
- [x] saldo atual e saldo projetado não fazem dupla contagem;
- [x] fluxo de caixa pode ser filtrado por período/status/tipo;
- [x] settlements podem ser consultados;
- [x] recorrências podem ser consultadas;
- [x] comprovantes da Fase 7.5 podem ser recuperados com autorização;
- [x] nenhum comprovante fica publicamente acessível;
- [x] endpoints são household-scoped;
- [x] tentativa cross-household é bloqueada;
- [x] listas extensas são paginadas;
- [x] frontend não replica regra financeira;
- [x] testes de backend, frontend e integração estão passando;
- [x] documentação de execução/deploy foi atualizada;
- [x] Compose/deploy existente foi preservado ou alterado apenas com justificativa explícita.

## Implementação concluída

O dashboard foi implementado em Next.js e servido pelo mesmo Caddy da aplicação. Ele utiliza tema escuro responsivo e oferece visão geral, transações, timeline de fluxo de caixa, investimentos, recorrências, liquidações, comprovantes e chat textual com o Sova.

O backend expõe `/api/dashboard/*` com sessão assinada em cookie `HttpOnly`. O household e a autoria são derivados da sessão; IDs de household nunca são aceitos do navegador. Valores monetários são calculados com `BigDecimal` e serializados como strings decimais.

O chat autenticado reutiliza o mesmo `AiConversationOrchestrator`, as mesmas tools locais e as mesmas validações financeiras do WhatsApp. As mensagens são persistidas por usuário, e o histórico recente é compartilhado entre dashboard e WhatsApp para manter continuidade de contexto sem permitir que o cliente escolha sua identidade.

A visão patrimonial separa deterministicamente:

- dinheiro livre em contas correntes, poupança, caixa e outras contas líquidas;
- dinheiro investido em contas do tipo `INVESTMENT`;
- dívidas a partir de saldos negativos e cartões de crédito;
- patrimônio líquido, resultado realizado e percentual investido.

A timeline consolidada distingue previsto e realizado e deriva no backend os estados `PENDING`, `SETTLED` e `OVERDUE`. Seus filtros de período, tipo, status, categoria e recorrência são enviados à API. Transações e entradas de fluxo de caixa possuem criação, edição e exclusão pelas mesmas regras de aplicação usadas pelo WhatsApp.

O formulário de novo registro é unificado para receitas e despesas. A opção booleana `É recorrente` cria uma entrada mensal de fluxo de caixa, usando o dia da data inicial como dia da recorrência; quando desmarcada, o registro continua sendo uma transação realizada. A edição preserva o tipo original para não converter nem duplicar registros existentes.

A implementação inicial do dashboard não exigiu migration porque o tipo de conta `INVESTMENT`, transações, fluxo de caixa, settlements e comprovantes já existiam no schema. O chat adicionou a migration `V12__dashboard_chat.sql` para o histórico web. O deploy adicionou apenas o container `sova-web` ao Compose e o roteamento correspondente no Caddy.

---

## Instruções de implementação para o Codex

Antes de alterar arquivos:

1. mapear a estrutura atual do backend;
2. procurar qualquer frontend existente;
3. identificar autenticação e household context;
4. identificar read/query layer;
5. revisar o cálculo atual de saldo e projeção;
6. revisar `CashFlowSettlement`;
7. revisar `Attachment`/comprovantes da Fase 7.5;
8. revisar Compose e pipeline de deploy.

Depois, apresentar um plano fechado de implementação antes de editar.

Durante a implementação:

- preferir read models dedicados a chamadas excessivamente granulares;
- preservar o domínio como fonte de verdade;
- evitar regra monetária no JavaScript/TypeScript do cliente;
- usar tipos monetários seguros;
- não criar storage público;
- não criar segundo backend;
- não alterar infraestrutura sem necessidade;
- não quebrar o fluxo WhatsApp existente.

Ao final:

1. rodar testes;
2. rodar build do frontend;
3. validar Compose;
4. listar endpoints criados;
5. listar telas criadas;
6. listar arquivos/migrations alterados;
7. demonstrar isolamento entre households;
8. demonstrar um fluxo previsto → liquidado → dashboard;
9. demonstrar recuperação autorizada de comprovante, se a Fase 7.5 já estiver instalada.
