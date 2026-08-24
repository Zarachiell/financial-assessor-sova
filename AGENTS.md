# AGENTS.md — Sova

Este arquivo define as regras operacionais para qualquer agente de código trabalhando no repositório do Sova.

As instruções deste arquivo são obrigatórias salvo quando uma tarefa específica declarar explicitamente o contrário.

Antes de iniciar qualquer trabalho no repositório, releia este arquivo, incluindo a convenção de commits definida na seção 34.

---

## 1. Contexto do projeto

O **Sova** é um assessor financeiro privado de uso pessoal.

Características atuais:

- 2 usuários autorizados;
- ambos pertencem ao mesmo `household`;
- acesso primário via WhatsApp;
- painel web em uma fase posterior;
- execução inicial em uma Hetzner CX23;
- não é um SaaS;
- não existe requisito de multi-tenancy comercial;
- não implementar abstrações especulativas para futuros clientes.

Antes de implementar qualquer feature relevante, consulte:

- `docs/SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/ROADMAP.md`
- ADRs existentes em `docs/ADR/`

Quando houver conflito entre documentação:

1. instrução explícita da tarefa atual;
2. `AGENTS.md`;
3. ADR mais recente aplicável;
4. `SPEC.md`;
5. `ARCHITECTURE.md`;
6. `ROADMAP.md`.

---

## 2. Filosofia de implementação

Priorizar:

- simplicidade;
- legibilidade;
- previsibilidade;
- domínio explícito;
- segurança de dados financeiros;
- baixo custo operacional;
- facilidade de manutenção.

Evitar:

- overengineering;
- abstrações prematuras;
- microservices sem justificativa;
- dependências desnecessárias;
- frameworks introduzidos apenas por conveniência local;
- infraestrutura distribuída para problemas que PostgreSQL resolve adequadamente.

O sistema deve permanecer um **modular monolith** enquanto não existir um motivo concreto para mudar.

---

## 3. Stack oficial

### Backend

- Kotlin
- JDK 21
- Ktor
- Gradle Kotlin DSL
- kotlinx.serialization
- Ktor Client

### Persistência

- PostgreSQL
- Flyway
- jOOQ

### Integrações

- Twilio para WhatsApp
- Groq como provider de IA inicial
- Ollama como provider self-hosted futuro

### Infraestrutura

- Docker Compose
- Caddy

### Frontend

Quando iniciado:

- Next.js
- TypeScript

Não substituir componentes da stack oficial sem uma decisão arquitetural explícita.

---

## 4. Estrutura arquitetural

O backend deve seguir separação por responsabilidade, sem dependências invertidas incorretamente.

Estrutura conceitual:

```text
domain
   ↑
application
   ↑
infrastructure
   ↑
delivery
```

O domínio não pode depender de:

- Ktor;
- Twilio;
- Groq;
- Ollama;
- PostgreSQL;
- jOOQ;
- HTTP;
- JSON de providers externos.

Integrações externas devem implementar ports/interfaces definidas internamente.

Exemplo:

```kotlin
interface AiProvider {
    suspend fun complete(request: AiRequest): AiResponse
}
```

Implementações:

```text
GroqAiProvider
OllamaAiProvider
```

O domínio não deve saber qual provider está ativo.

---

## 5. Regra central sobre IA

A IA é uma camada de interpretação e interação.

Ela não é a fonte da verdade financeira.

A IA pode:

- interpretar linguagem natural;
- classificar intenção;
- extrair entidades;
- selecionar tools;
- gerar respostas em linguagem natural;
- ajudar na categorização.

A IA não pode:

- acessar PostgreSQL diretamente;
- gerar SQL para execução;
- determinar `userId`;
- determinar `householdId`;
- acessar secrets;
- executar URLs arbitrárias;
- ignorar regras de domínio;
- persistir dados diretamente;
- calcular valores financeiros que possam ser calculados deterministicamente pelo backend.

Toda operação de escrita originada por IA deve passar por:

```text
LLM
 ↓
Tool Call
 ↓
validação estrutural
 ↓
Application/Domain Command
 ↓
validação de negócio
 ↓
persistência
```

---

## 6. Tool calling

Utilizar **local tool calling**.

O provider de IA nunca recebe endpoints internos para executar tools.

Fluxo:

```text
Sova -> Groq
Groq -> tool_call
Sova -> executa tool local
Sova -> Groq com resultado mínimo necessário
Groq -> resposta natural
```

Toda tool deve possuir:

- nome estável;
- descrição clara;
- schema de argumentos;
- validação;
- handler explícito;
- testes.

Nunca criar uma tool genérica como:

```text
execute_sql
run_query
http_request
execute_code
```

Tools devem representar capacidades específicas do domínio.

---

## 7. Identidade e autorização

O sistema possui:

- 1 household;
- 2 users;
- 2 WhatsappIdentities.

O número do WhatsApp identifica o usuário.

Nunca confiar em `userId` ou `householdId` fornecido pela IA ou pelo cliente quando esses valores puderem ser derivados do contexto autenticado.

Exemplo correto:

```kotlin
handler.execute(
    householdId = context.householdId,
    createdByUserId = context.userId,
    amount = args.amount
)
```

Exemplo proibido:

```kotlin
handler.execute(
    householdId = args.householdId,
    createdByUserId = args.userId
)
```

Toda operação financeira deve registrar autoria quando aplicável.

---

## 8. Dinheiro

Nunca utilizar:

- `Double`;
- `Float`;

para representar valores monetários.

Utilizar:

```kotlin
BigDecimal
```

Definir escala e regras de arredondamento explicitamente quando necessário.

Moeda deve ser explícita quando o contexto exigir.

Inicialmente, o default pode ser `BRL`, desde que essa decisão esteja centralizada e não espalhada pelo código.

---

## 9. Datas e horários

Datas financeiras devem ser tratadas de forma explícita.

Distinguir:

- data civil da transação;
- instante técnico de criação;
- timezone.

Não inferir silenciosamente uma data quando a interpretação for ambígua.

Para contexto do usuário, considerar timezone configurável do household.

Inicialmente:

```text
America/Fortaleza
```

Persistir timestamps técnicos preferencialmente em UTC.

---

## 10. Banco de dados

PostgreSQL é a fonte da verdade.

Toda alteração de schema deve utilizar migration Flyway.

Regras:

- migrations nunca devem ser editadas depois de aplicadas;
- novas alterações geram nova migration;
- constraints importantes pertencem também ao banco;
- utilizar foreign keys;
- utilizar unique constraints quando aplicável;
- idempotência deve ser garantida estruturalmente quando possível.

Exemplo obrigatório:

`Twilio MessageSid` deve possuir constraint única.

Não depender apenas de checks em memória para impedir duplicidade.

---

## 11. jOOQ

Utilizar jOOQ para acesso SQL.

Evitar criar uma camada de repository genérica que esconda completamente SQL sem benefício claro.

Preferir queries explícitas e tipadas.

Não introduzir ORM adicional.

---

## 12. WhatsApp / Twilio

Todo webhook Twilio deve:

1. validar `X-Twilio-Signature`;
2. validar o remetente;
3. verificar idempotência;
4. persistir a mensagem recebida;
5. responder rapidamente;
6. evitar processamento pesado no request quando houver mídia.

Números de WhatsApp não autorizados não podem utilizar o sistema.

Não logar secrets da Twilio.

---

## 13. Processamento assíncrono

Não introduzir inicialmente:

- Kafka;
- RabbitMQ;
- Redis Streams;
- SQS.

Quando jobs forem necessários, usar PostgreSQL.

Padrão recomendado:

```sql
FOR UPDATE SKIP LOCKED
```

Criar infraestrutura adicional somente mediante necessidade comprovada.

---

## 14. Áudio, imagem e documentos

Áudio, imagem e documentos devem convergir para o mesmo domínio financeiro.

Não duplicar regras de negócio por canal.

Exemplo:

```text
WhatsApp Text ───┐
Audio -> STT ────┼──> interpretação -> Domain Command
Image -> Vision ─┘
```

Metadados de origem devem ser preservados.

---

## 15. Importação de extratos

Prioridade de processamento:

1. parser determinístico;
2. normalização;
3. regras locais;
4. IA apenas quando necessário.

Para:

- OFX;
- CSV;

preferir parser determinístico.

Não enviar extratos completos para LLM sem necessidade.

Itens importados devem passar por staging:

```text
StatementImport
 ↓
StatementImportItem
 ↓
review / validation
 ↓
Transaction
```

Não inserir diretamente transações finais durante parsing.

---

## 16. Privacidade

Princípio:

> enviar ao provider externo somente os dados necessários para executar a tarefa atual.

Evitar enviar:

- histórico financeiro completo;
- extratos completos;
- dados pessoais desnecessários;
- credenciais;
- dumps;
- logs internos.

Quando possível, calcular agregações localmente.

Exemplo:

Pergunta:

> Quanto gastamos com restaurante?

Preferir enviar à IA:

```json
{
  "total": 872.30,
  "count": 14
}
```

em vez das 14 transações completas.

---

## 17. Secrets e configuração

Secrets nunca entram no Git.

Utilizar environment variables.

Manter `.env.example` sem valores sensíveis.

Exemplos:

```text
DATABASE_URL
TWILIO_ACCOUNT_SID
TWILIO_AUTH_TOKEN
TWILIO_WHATSAPP_SENDER
GROQ_API_KEY
SOVA_AI_PROVIDER
```

Nunca imprimir secrets em logs, exceptions ou testes.

---

## 18. Testes

Toda regra de domínio relevante deve possuir teste.

Prioridades:

1. testes unitários de domínio;
2. testes de application services;
3. testes de integração PostgreSQL;
4. testes de adapters externos com mocks/fakes;
5. testes end-to-end somente para fluxos críticos.

Não fazer chamadas reais para:

- Groq;
- Twilio;

durante testes automatizados comuns.

Criar fakes determinísticos para providers externos.

---

## 19. Qualidade de código Kotlin

Preferir:

- tipos explícitos para conceitos de domínio;
- funções pequenas;
- imutabilidade;
- sealed types quando fizer sentido;
- value objects quando eliminarem classes de bugs;
- composition over inheritance.

Evitar:

- classes `Utils`;
- singletons globais;
- service locator;
- reflection sem necessidade;
- exceptions como fluxo normal;
- `!!` salvo em situações comprovadamente seguras;
- strings mágicas espalhadas.

Não criar interfaces para todas as classes automaticamente.

Interfaces existem para boundaries, substituição relevante ou abstrações de domínio claras.

---

## 20. Erros

Distinguir:

- erro de validação;
- erro de domínio;
- erro de infraestrutura;
- erro de provider externo;
- erro inesperado.

Mensagens enviadas ao usuário não devem expor:

- stack traces;
- SQL;
- secrets;
- detalhes internos.

Logs devem preservar informação suficiente para diagnóstico usando correlation IDs.

---

## 21. Logging

Utilizar logs estruturados.

Campos úteis:

- correlationId;
- messageSid;
- userId;
- householdId;
- toolName;
- durationMs;
- result/status.

Não logar conteúdo financeiro completo sem necessidade.

Nunca logar:

- API keys;
- auth tokens;
- passwords;
- conteúdo binário de anexos.

---

## 22. Observabilidade

Não introduzir uma plataforma pesada no MVP.

Preparar o código para futura instrumentação.

OpenTelemetry pode ser adicionado quando houver necessidade concreta.

---

## 23. Performance

O ambiente inicial possui recursos limitados:

```text
2 vCPU
4 GB RAM
40 GB disk
```

Evitar:

- processos JVM desnecessários;
- caches enormes;
- pools excessivos;
- modelos LLM locais como dependência obrigatória;
- serviços redundantes.

O MVP deve rodar confortavelmente nessa máquina usando IA externa.

---

## 24. Docker

O ambiente local e inicial de produção deve poder subir com:

```bash
docker compose up -d
```

Containers iniciais esperados:

```text
caddy
sova-api
postgres
```

Não criar múltiplos containers de backend sem necessidade.

---

## 25. API

Endpoints devem ser coesos e versionáveis.

Não criar endpoints administrativos genéricos capazes de burlar regras de domínio.

Webhooks externos devem ficar claramente separados das APIs internas/web.

---

## 26. ADRs

Decisões arquiteturais relevantes devem gerar ADR.

Formato:

```text
docs/ADR/NNNN-titulo.md
```

Cada ADR deve conter:

- contexto;
- decisão;
- alternativas consideradas;
- consequências.

Não criar ADR para decisões triviais de implementação.

---

## 27. Workflow do agente

Antes de implementar uma tarefa:

1. ler a tarefa;
2. consultar documentação aplicável;
3. identificar o milestone atual no roadmap;
4. inspecionar o código existente;
5. implementar apenas o escopo solicitado;
6. adicionar/ajustar testes;
7. executar testes;
8. atualizar documentação somente quando necessário;
9. reportar alterações e riscos.

Não avançar automaticamente para a próxima fase do roadmap.

Exemplo:

Se a tarefa for:

> Implemente a Fase 1.

não implementar funcionalidades da Fase 2 sem solicitação explícita.

---

## 28. Alterações arquiteturais

Não alterar silenciosamente decisões estruturais.

Exemplos que exigem discussão/ADR:

- Ktor -> Spring Boot;
- PostgreSQL -> outro banco;
- modular monolith -> microservices;
- jOOQ -> ORM;
- PostgreSQL jobs -> RabbitMQ;
- Groq -> provider exclusivo acoplado;
- mudança no modelo Household/User.

Quando uma implementação exigir mudança desse tipo, interromper a expansão de escopo e documentar o motivo.

---

## 29. Dependências

Antes de adicionar uma biblioteca:

1. verificar se a stdlib/framework atual resolve;
2. avaliar manutenção;
3. avaliar impacto de runtime;
4. evitar dependências para problemas triviais.

Toda nova dependência significativa deve ter justificativa clara.

---

## 30. Compatibilidade com providers de IA

Não utilizar classes do SDK Groq/OpenAI dentro do domínio ou application layer.

Converter payload externo para modelos internos na camada de infrastructure.

Exemplo:

```text
Groq JSON
   ↓
GroqAiProvider
   ↓
AiResponse
   ↓
Application
```

Isso deve permitir trocar:

```text
SOVA_AI_PROVIDER=groq
```

por:

```text
SOVA_AI_PROVIDER=ollama
```

sem alteração das regras financeiras.

---

## 31. Definition of Done

Uma tarefa não está concluída apenas porque compila.

Para ser considerada pronta deve, quando aplicável:

- compilar;
- passar testes;
- possuir migration;
- preservar idempotência;
- tratar erro relevante;
- respeitar segurança;
- não expor secrets;
- manter documentação consistente;
- não introduzir dívida arquitetural desnecessária.

---

## 32. Regras de escopo atuais

Neste estágio, não implementar sem solicitação explícita:

- multi-tenancy comercial;
- billing;
- subscriptions;
- admin SaaS;
- RBAC complexo;
- Kubernetes;
- service mesh;
- Kafka;
- RabbitMQ;
- Redis;
- Open Finance;
- mobile app;
- machine learning customizado;
- vector database;
- RAG genérico.

---

## 33. Princípio final

Quando houver duas soluções tecnicamente válidas, preferir a que:

1. possui menos moving parts;
2. é mais fácil de testar;
3. mantém regras financeiras fora da IA;
4. reduz exposição de dados;
5. roda melhor na CX23;
6. pode ser entendida rapidamente por outro engenheiro.

---

## 34. Convenção de commits

Antes de criar qualquer commit, utilizar o seguinte formato:

```text
<resumo curto e objetivo>

Why ?

- motivo da alteração ou necessidade da funcionalidade;
- contexto adicional relevante, quando existir.

How ?

- como os motivos descritos em `Why ?` foram atendidos;
- principais decisões de implementação e validações realizadas.
```

Regras:

- utilizar bullet points nas seções `Why ?` e `How ?`;
- manter o resumo na primeira linha;
- não adicionar `Co-authored-by` ou qualquer variação de coautoria;
- revisar a mensagem final com `git log -1 --format=full` após o commit.
