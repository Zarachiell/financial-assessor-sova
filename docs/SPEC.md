# Sova — Product & Domain Specification

## 1. Objetivo

O **Sova** é um assessor financeiro privado de uso pessoal, acessado prioritariamente via WhatsApp e, em uma segunda etapa, por um painel web.

O sistema será utilizado por **dois usuários autorizados** pertencentes ao mesmo núcleo financeiro (household). O objetivo não é construir um SaaS ou produto multi-tenant nesta fase.

## 2. Princípios

1. O WhatsApp é o canal primário.
2. O PostgreSQL é a fonte da verdade financeira.
3. A IA interpreta linguagem e seleciona ferramentas; ela não acessa diretamente o banco.
4. Regras financeiras, cálculos, validações e persistência são determinísticos.
5. Toda operação criada por IA deve passar por validação de domínio.
6. Toda informação importada deve manter rastreabilidade até sua origem.
7. Dados enviados a provedores externos de IA devem ser minimizados.
8. A arquitetura deve permitir substituir Gemini por um provider futuro sem alterar o domínio; Gemini é o único provider operacional atual.

## 3. Usuários e Household

O Sova possuirá inicialmente:

- 2 usuários;
- 1 household compartilhado;
- 2 números de WhatsApp autorizados.

Cada usuário terá sua própria identidade, mas ambos poderão consultar e manipular o mesmo conjunto financeiro.

### Regras

- Toda operação deve registrar `created_by_user_id`.
- Toda operação financeira pertence a um `household_id`.
- O número do WhatsApp determina o usuário autenticado.
- Números não autorizados devem ser rejeitados ou ignorados.

## 4. Regras de negócio

### RN-01 — Cadastro de despesa

Como usuário autorizado, quero enviar uma mensagem ao Sova via WhatsApp e cadastrar uma despesa por:

- texto;
- áudio;
- imagem.

### RN-02 — Cadastro de fluxo de caixa

Como usuário autorizado, quero cadastrar entradas ou saídas futuras e recorrentes via:

- texto;
- áudio;
- imagem.

### RN-03 — Consultas financeiras via WhatsApp

Como usuário autorizado, quero perguntar ao Sova sobre as finanças do household.

Exemplos:

- Quanto gastamos este mês?
- Quanto gastamos com restaurante?
- Qual nosso saldo atual?
- Como ficará nosso caixa no dia 25?
- Quais foram nossos maiores gastos?
- Estamos gastando mais que no mês passado?

### RN-04 — Dashboard

Como usuário autorizado, quero visualizar informações financeiras por meio de um painel web.

### RN-05 — Operações pelo dashboard

Como usuário autorizado, quero:

- cadastrar despesas;
- cadastrar receitas;
- gerenciar fluxo de caixa;
- gerar relatórios.

### RN-06 — Assistente no dashboard

Como usuário autorizado, quero conversar com o Sova no painel e consultar informações financeiras.

### RN-07 — Importação de extratos

Como usuário autorizado, quero importar extratos via WhatsApp ou dashboard.

Formatos prioritários:

1. OFX;
2. CSV;
3. PDF;
4. imagem.

### RN-08 — Validação de operações da IA

Toda operação financeira sugerida por IA deve ser validada pelo domínio antes de persistida.

### RN-09 — Rastreabilidade

Toda transação deve registrar sua origem.

Exemplos:

- WHATSAPP_TEXT;
- WHATSAPP_AUDIO;
- WHATSAPP_IMAGE;
- DASHBOARD;
- STATEMENT_IMPORT.

### RN-10 — Correção de registros

Como usuário autorizado, quero corrigir ou excluir logicamente uma transação cadastrada por engano sem perder a autoria da alteração.

### RN-11 — Múltiplos lançamentos textuais

Uma única mensagem pode originar múltiplas transações, desde que cada item seja estruturalmente validado e possua chave idempotente própria dentro da mensagem.

### RN-12 — Liquidação de fluxo de caixa

Como usuário autorizado, quero consultar ocorrências futuras pendentes e pagas e marcar uma ocorrência como paga ou recebida na data real.

A liquidação deve:

- preservar a previsão original;
- registrar autor, vencimento e instante da operação;
- criar atomicamente a transação realizada;
- afetar o saldo na data real;
- remover a ocorrência paga das projeções pendentes;
- não encerrar as demais ocorrências de uma série mensal.

## 5. Modelo de domínio inicial

### Household

Representa o núcleo financeiro compartilhado.

Campos mínimos:

- id;
- name;
- created_at.

Transações corrigidas ou excluídas também registram autoria e instante da modificação. Exclusão é lógica. Transações geradas por liquidação possuem origem `CASH_FLOW_SETTLEMENT`.

### User

Campos mínimos:

- id;
- household_id;
- name;
- active;
- created_at.

### WhatsappIdentity

Campos mínimos:

- id;
- user_id;
- phone_number;
- active.

### Account

Representa uma conta financeira.

Exemplos:

- conta corrente;
- carteira;
- cartão;
- conta de investimento.

### Category

Representa a categoria financeira de uma transação.

### Transaction

Representa um evento financeiro ocorrido.

Campos principais:

- id;
- household_id;
- created_by_user_id;
- account_id;
- type;
- amount;
- description;
- category_id;
- occurred_at;
- source;
- source_reference_id;
- created_at.

Tipos:

- INCOME;
- EXPENSE.

### CashFlowEntry

Representa uma entrada ou saída esperada.

Campos principais:

- id;
- household_id;
- created_by_user_id;
- type;
- amount;
- description;
- category_id;
- recurrence;
- start_date;
- end_date;
- active.

### CashFlowSettlement

Representa a liquidação de uma ocorrência específica de `CashFlowEntry` e aponta para a `Transaction` real criada atomicamente.

### WhatsappMessage

Mantém rastreabilidade da conversa recebida ou enviada.

### Attachment

Metadados de imagens, áudios e documentos recebidos.

### StatementImport

Representa uma importação de extrato.

### StatementImportItem

Representa cada lançamento detectado antes da confirmação final.

## 6. Uso da IA

A IA deverá atuar por meio de um contrato abstrato `AiProvider`.

Implementações previstas:

- `GeminiAiProvider`;
- `OllamaAiProvider`.

A aplicação deverá utilizar **local tool calling**.

Fluxo:

1. Sova envia mensagem + definição das tools ao provider;
2. provider retorna uma `tool_call`;
3. Sova valida a chamada;
4. Sova executa a tool localmente;
5. Sova envia apenas o resultado necessário ao provider;
6. provider produz a resposta final.

A IA nunca recebe:

- credenciais PostgreSQL;
- acesso direto ao banco;
- endpoint interno para execução de tools;
- capacidade de executar SQL arbitrário.

## 7. Tools iniciais

### Leitura

- `get_current_balance`
- `get_expenses`
- `get_income`
- `get_transactions`
- `get_category_summary`
- `get_cash_flow_projection`
- `get_cash_flow_entries`

### Escrita

- `create_transaction`
- `create_cash_flow_entry`
- `update_transaction`
- `delete_transaction`
- `update_cash_flow_entry`
- `delete_cash_flow_entry`
- `settle_cash_flow_occurrence`

## 8. Confiança e confirmação

Operações inequívocas podem ser cadastradas automaticamente.

Exemplo:

> Gastei R$ 32 no almoço hoje.

Operações ambíguas devem pedir confirmação.

Exemplo:

> Acho que gastei uns R$ 200 ontem.

A aplicação deve poder registrar confiança por campo ou por interpretação.

## 9. Requisitos não funcionais iniciais

- Kotlin/JVM;
- PostgreSQL;
- execução em Docker Compose;
- HTTPS;
- validação de webhook Twilio;
- migrations versionadas;
- logs estruturados;
- idempotência para mensagens recebidas;
- suporte a restart sem perda de jobs;
- secrets fora do repositório.

## 10. Fora do escopo inicial

- SaaS;
- cobrança;
- assinatura;
- multi-tenancy comercial;
- organizações;
- RBAC complexo;
- Open Finance;
- aplicativos mobile nativos;
- múltiplos households por usuário.

Esses itens podem ser revisitados no futuro sem direcionar a arquitetura do MVP.
