# Phase 01 — WhatsApp Text

## Status
Implemented with automated validation complete. Manual Twilio Sandbox validation pending.

## Objective
Permitir que os dois usuários autorizados enviem mensagens textuais ao Sova via WhatsApp/Twilio e recebam resposta.

Nesta fase não existe IA nem domínio financeiro.

Fluxo funcional de validação:
```text
Usuário: ping
Sova: pong
```

## In scope
- webhook Twilio;
- validação de assinatura;
- identidade por número;
- 2 usuários;
- 1 household;
- persistência de mensagens;
- idempotência por `MessageSid`;
- envio de resposta;
- migrations;
- testes com fake/mock.

## Out of scope
- Groq/Ollama;
- tool calling;
- Transaction;
- CashFlowEntry;
- áudio/imagem;
- frontend;
- autenticação web.

## Domain additions

### Household
```text
id
name
created_at
```

### User
```text
id
household_id
name
active
created_at
```

### WhatsappIdentity
```text
id
user_id
phone_number
normalized_phone_number
active
created_at
```

### WhatsappMessage
```text
id
message_sid
direction
user_id
from_address
to_address
body
received_at
created_at
```

`direction`:
```text
INBOUND
OUTBOUND
```

## Household model
```text
1 Household
2 Users
2 WhatsappIdentities
```

Os números não devem ser hardcoded no código.

Para celulares brasileiros, `normalized_phone_number` torna equivalentes as identidades com e sem o nono dígito. `phone_number` preserva o valor configurado, e `from_address` preserva o valor efetivamente recebido no webhook.

## Endpoint
```http
POST /webhooks/twilio/whatsapp
Content-Type: application/x-www-form-urlencoded
```

Campos relevantes:
```text
MessageSid
AccountSid
From
To
Body
NumMedia
```

## Request flow
```text
Twilio
  ↓
validate X-Twilio-Signature
  ↓
extract payload
  ↓
check idempotency
  ↓
resolve WhatsappIdentity
  ↓
resolve User + Household
  ↓
persist inbound
  ↓
text handler
  ↓
send response via Twilio adapter
```

## Security
- validar `X-Twilio-Signature` usando mecanismo/SDK oficial;
- assinatura inválida -> 403;
- não persistir mensagem inválida;
- não enviar resposta para webhook inválido;
- não logar `TWILIO_AUTH_TOKEN`.

## Authorized senders
Somente `WhatsappIdentity.active = true`.

Sender desconhecido/inativo:
- não executa lógica funcional;
- não recebe informação privada;
- pode retornar HTTP 200 vazio para evitar retry desnecessário, desde que testado/documentado.

## Idempotency
`MessageSid` deve ser protegido por constraint única no PostgreSQL.

Reentrega não pode causar:
- segunda linha inbound;
- segundo processamento lógico;
- segunda resposta funcional.

## Ping/Pong
`ping` com trim/case-insensitive retorna:
```text
pong
```

Outras mensagens podem retornar uma resposta neutra, sem NLP.

## Outbound port
Exemplo conceitual:
```kotlin
interface WhatsappMessageSender {
    suspend fun send(to: String, body: String): SendMessageResult
}
```

Implementação:
```text
TwilioWhatsappMessageSender
```

Application não deve depender diretamente do SDK Twilio.

## Configuration
```text
TWILIO_ACCOUNT_SID
TWILIO_AUTH_TOKEN
TWILIO_WHATSAPP_SENDER
SOVA_HOUSEHOLD_NAME
SOVA_USER_A_NAME
SOVA_USER_A_WHATSAPP
SOVA_USER_B_NAME
SOVA_USER_B_WHATSAPP
```

As duas identidades são provisionadas no PostgreSQL durante o startup. Números fora da configuração atual são desativados, e nenhum número real fica hardcoded no Kotlin.

## Migrations
Criar:
```text
households
users
whatsapp_identities
whatsapp_messages
```

Com PKs, FKs, unique constraints e integridade adequada.

## Tests
- lookup por WhatsappIdentity;
- equivalência de celular brasileiro com e sem o nono dígito;
- ativo/inativo;
- ping -> pong;
- duplicate não reprocessa;
- assinatura inválida -> 403;
- form-urlencoded;
- unique `MessageSid`;
- persistência inbound;
- fake de envio Twilio.

A suíte normal não chama Twilio real.

## Acceptance Criteria

### AC-01-01
Webhook válido + número autorizado deve ser aceito.

**PASS** — teste de integração percorre Ktor, application e PostgreSQL para os dois usuários.

### AC-01-02
Assinatura inválida -> 403, sem persistência e sem resposta.

**PASS** — validado na rota e no smoke test Docker através do Caddy.

### AC-01-03
User A envia `ping` e recebe `pong`.

**PASS** — validado com fake determinístico de envio.

### AC-01-04
User B envia `ping` e recebe `pong`.

**PASS** — validado com fake determinístico de envio.

### AC-01-05
User A e User B resolvem para o mesmo `household_id`.

**PASS** — comprovado por teste de integração PostgreSQL.

### AC-01-06
A mensagem registra corretamente o `user_id` autor.

**PASS** — comprovado para os dois usuários na persistência real.

### AC-01-07
Sender não autorizado não executa lógica funcional nem recebe dados privados.

**PASS** — retorna HTTP 200 vazio, sem persistência e sem envio.

### AC-01-08
Mesmo `MessageSid` entregue novamente não gera duplicidade nem segunda resposta lógica.

**PASS** — constraint única e `ON CONFLICT DO NOTHING`, validados ponta a ponta.

### AC-01-09
Webhook válido de usuário autorizado gera persistência inbound.

**PASS** — validado no PostgreSQL com direção, autoria e conteúdo.

### AC-01-10
Application depende de um port de WhatsApp, não diretamente do SDK Twilio.

**PASS** — `WhatsappMessageSender` é o port; imports Twilio existem somente em infrastructure.

### AC-01-11
Não existe dependência funcional de Groq/Ollama.

**PASS** — nenhuma implementação ou chamada de IA foi adicionada.

### AC-01-12
Não existem Transaction/CashFlowEntry antecipadas.

**PASS** — nenhum tipo ou schema financeiro foi adicionado.

## Definition of Done
Migrations aplicam, os dois usuários podem ser provisionados, webhook funciona, assinatura é validada, sender é autorizado, idempotência está garantida no banco, inbound é persistido, outbound usa adapter, ping/pong funciona e todos os testes passam.

**Resultado automatizado:** atendido com 28 testes, fat JAR e stack Docker saudáveis. A confirmação final no canal real depende da configuração do sender Twilio e permanece registrada abaixo.

## Manual Sandbox Validation

Status: pending real Twilio credentials, the two authorized phone numbers, and a webhook strategy that does not divert the sender already used by the other bot.

User A:
```text
ping -> pong
```

User B:
```text
ping -> pong
```

Número não autorizado:
- nenhuma informação privada;
- nenhum acesso funcional.

Checklist:

- [ ] User A recebe `pong`;
- [ ] User B recebe `pong`;
- [ ] número não autorizado não recebe informação privada;
- [ ] logs do teste não expõem secrets.

## Codex instruction
> Implemente `docs/phases/01-whatsapp-text.md`. Respeite `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md` e ADRs aplicáveis. Não implemente Groq, domínio financeiro, áudio, imagem ou fases posteriores. Use testes/fakes para Twilio. Ao finalizar, reporte cada AC como PASS ou FAIL, migrations criadas, testes executados e divergências encontradas.
