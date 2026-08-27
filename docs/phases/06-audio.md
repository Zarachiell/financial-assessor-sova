# Phase 06 — Audio

## Status
Implemented in code. Manual Twilio Sandbox validation remains pending.

## 1. Objective

Permitir que Pai e Mãe enviem mensagens de áudio pelo WhatsApp e que o Sova processe essas mensagens usando o mesmo pipeline textual já existente.

A arquitetura deve tratar áudio apenas como uma nova forma de produzir texto.

```text
WhatsApp audio
   ↓
Twilio
   ↓
Sova webhook
   ↓
download media
   ↓
SpeechToTextProvider
   ↓
transcription
   ↓
ConversationOrchestrator
   ↓
tools/domain
   ↓
natural response
   ↓
WhatsApp
```

Não duplicar regras financeiras, tools ou lógica de domínio.

## 2. Scope

### In scope
- detectar áudio inbound;
- persistir metadata do attachment;
- baixar mídia da Twilio;
- `SpeechToTextProvider`;
- `GeminiSpeechToTextProvider`;
- transcrição;
- reutilização do pipeline textual;
- processamento assíncrono quando necessário;
- idempotência;
- retry controlado;
- limites de tamanho/duração;
- testes com fakes;
- observabilidade mínima.

### Out of scope
- TTS;
- resposta em áudio;
- diarization;
- tradução;
- imagem/OCR;
- Whisper local;
- Ollama;
- Redis/Kafka/RabbitMQ.

## 3. Architectural Principle

Correto:

```text
Audio
  ↓
SpeechToText
  ↓
Text
  ↓
existing ConversationOrchestrator
```

Proibido:

```text
Audio
  ↓
separate financial parser
  ↓
separate transaction logic
```

## 4. Twilio Media Detection

Campos relevantes:

```text
NumMedia
MediaUrl0
MediaContentType0
```

Nesta fase, processar apenas o primeiro attachment de áudio relevante.

Se houver múltiplos attachments:
- registrar metadata;
- processar apenas o primeiro áudio;
- manter comportamento explícito.

## 5. Supported Audio Types

Lista centralizada, por exemplo:

```text
audio/ogg
audio/mpeg
audio/mp4
audio/wav
audio/webm
```

Tipo não suportado:
- não transcrever;
- responder de forma controlada;
- registrar evento técnico.

## 6. Attachment Model

Se ainda não existir, criar estrutura mínima:

```text
id
whatsapp_message_id
media_url
content_type
media_type
size_bytes?
status
created_at
updated_at
```

`media_type`:

```text
AUDIO
IMAGE
DOCUMENT
OTHER
```

`status`:

```text
RECEIVED
DOWNLOADING
DOWNLOADED
PROCESSING
PROCESSED
FAILED
```

Não armazenar binary no PostgreSQL.

## 7. Media Download

Criar boundary explícito:

```kotlin
interface WhatsappMediaDownloader {
    suspend fun download(mediaUrl: String): DownloadedMedia
}
```

Implementação:

```text
TwilioWhatsappMediaDownloader
```

Application não deve depender diretamente do cliente HTTP/Twilio.

## 8. Media Security

- usar autenticação exigida pela Twilio;
- não logar auth token;
- não logar media URL completa quando sensível;
- não aceitar URL arbitrária fornecida pelo LLM;
- downloader só recebe URL originada de webhook Twilio validado.

## 9. Temporary Storage

Se precisar salvar em disco:

```text
/tmp/sova/media/<generated-id>
```

Regras:
- nome gerado internamente;
- cleanup após processamento;
- cleanup também em falha quando possível;
- não reter áudio bruto permanentemente por padrão.

## 10. SpeechToTextProvider

Contrato provider-agnostic:

```kotlin
interface SpeechToTextProvider {
    suspend fun transcribe(
        audio: AudioInput,
        context: SpeechToTextContext
    ): TranscriptionResult
}
```

Contexto pode conter:

```text
languageHint
mimeType
filename
```

Não enviar `userId`/`householdId` ao provider.

## 11. GeminiSpeechToTextProvider

Implementar:

```text
GeminiSpeechToTextProvider
```

Configuração:

```text
GOOGLE_CLOUD_PROJECT
GOOGLE_CLOUD_LOCATION
GOOGLE_APPLICATION_CREDENTIALS
GEMINI_AUDIO_MODEL
```

Modelo inicial:

```text
gemini-3.5-flash-lite
```

Modelo deve ser configurável.

## 12. Language

Idioma esperado:

```text
pt-BR
```

Quando possível, usar hint de português.

Não traduzir; apenas transcrever.

## 13. Transcription Result

Modelo interno mínimo:

```text
text
provider
model
durationMs
confidence?
language?
```

Não inventar confidence.

## 14. Transcription Persistence

Persistir a transcrição apenas se útil para:
- rastreabilidade;
- debugging;
- reprocessamento.

Evitar duplicação de conteúdo.

## 15. Async Processing

Áudio não deve bloquear o webhook por tempo excessivo.

Fluxo recomendado:

```text
Webhook
  ↓
validate
  ↓
persist inbound + attachment
  ↓
enqueue processing job
  ↓
return HTTP success
```

Depois:

```text
worker
  ↓
download
  ↓
transcribe
  ↓
ConversationOrchestrator
  ↓
reply
```

Continuar usando PostgreSQL como fila.

## 16. Processing Job

Modelo mínimo:

```text
id
type
reference_id
status
attempts
available_at
last_error
created_at
updated_at
```

Tipo:

```text
PROCESS_AUDIO_MESSAGE
```

Status:

```text
PENDING
PROCESSING
COMPLETED
FAILED
```

## 17. Retry Policy

Retry apenas para falhas transitórias:
- timeout;
- HTTP 429;
- HTTP 5xx;
- falha temporária de download.

Sugestão:

```text
maxAttempts = 3
```

Não retry indefinidamente.

Não duplicar side effects financeiros.

## 18. End-to-End Idempotency

O mesmo `MessageSid` não pode causar:
- dois attachments equivalentes;
- dois processamentos;
- duas transações;
- duas respostas funcionais.

Proteger em níveis adequados:
```text
MessageSid
Attachment
ProcessingJob
sourceReferenceId
```

## 19. Pipeline Integration

Transcrição:

```text
Gastei 35 reais no almoço hoje
```

deve seguir o mesmo fluxo de mensagem textual equivalente.

Contexto adicional:

```text
source = WHATSAPP_AUDIO
sourceReferenceId = MessageSid
```

## 20. Source Tracking

Adicionar source:

```text
WHATSAPP_AUDIO
```

Transaction criada por áudio deve registrar essa origem e `source_reference_id`.

## 21. User Experience

Preferir resposta final direta se a latência for aceitável.

Se houver latência perceptível, pode haver acknowledgement curto, por exemplo:

> Pai, peguei o áudio. Vou farejar isso aqui. 🐾

Evitar mensagem intermediária se não for necessária.

## 22. Empty / Invalid Transcription

Se resultado for vazio/ininteligível:
- não chamar tools financeiras;
- pedir reenvio.

Exemplo:

> Pai, meu ouvido de beagle falhou nessa. Não consegui entender o áudio. Tenta mandar de novo?

## 23. Size and Duration Limits

Definir configurações centralizadas:

```text
maxAudioBytes
maxAudioDurationMinutes
```

Sugestão inicial:

```text
maxAudioDurationMinutes = 5
```

O valor final deve respeitar limites Twilio/Gemini.

## 24. Privacy

Enviar ao Gemini somente o áudio necessário para transcrição.

Não enviar junto:
- histórico financeiro;
- household context;
- tools;
- system prompts.

Depois da transcrição, apenas o texto segue para o pipeline normal.

## 25. Logging

Metadata permitida:

```text
correlationId
messageSid
attachmentId
contentType
sizeBytes
provider
model
durationMs
status
attempt
```

Não logar:
- binary;
- API key;
- auth token;
- conteúdo completo do áudio;
- transcrição completa por padrão.

## 26. Error Handling

Distinguir:
- unsupported media;
- download failure;
- auth failure;
- file too large;
- STT timeout;
- rate limit;
- provider error;
- blank transcription;
- orchestrator error;
- outbound failure.

## 27. Tests

### Media detection
- sem mídia;
- um áudio;
- tipo suportado;
- tipo não suportado;
- múltiplos attachments.

### Downloader
- sucesso;
- auth;
- timeout;
- cleanup.

### SpeechToTextProvider
Com fake:
- transcrição válida;
- blank;
- erro;
- timeout.

### GeminiSpeechToTextProvider
Testar request/response sem chamada real.

### Job processing
- pending -> processing -> completed;
- retry;
- failed após max attempts.

### End-to-end
Com fakes:

```text
audio -> "Gastei 35 reais no almoço"
```

deve criar uma única Transaction com:

```text
source = WHATSAPP_AUDIO
```

### Idempotency
Mesmo `MessageSid` duas vezes:
```text
1 processamento
1 transaction
1 resposta funcional
```

## 28. Mandatory Scenarios

### A — Expense audio
> Gastei 35 reais no almoço hoje.

### B — Query audio
> Quanto gastamos hoje?

### C — Invalid audio
Áudio sem fala compreensível.

### D — Unsupported media
Mídia não suportada.

### E — Duplicate webhook
Mesmo MessageSid entregue novamente.

## 29. Acceptance Criteria

### AC-06-01
Áudio suportado é detectado corretamente.

### AC-06-02
Metadata do attachment é persistida.

### AC-06-03
Download passa por boundary específico.

### AC-06-04
Application não depende diretamente da API HTTP da Twilio para download.

### AC-06-05
Existe `SpeechToTextProvider`.

### AC-06-06
`GeminiSpeechToTextProvider` implementa `SpeechToTextProvider`.

### AC-06-07
Transcrição reutiliza o `ConversationOrchestrator` textual.

### AC-06-08
Transaction criada por áudio usa `source = WHATSAPP_AUDIO`.

### AC-06-09
Blank transcription não executa tool financeira.

### AC-06-10
Tipo não suportado não é enviado ao STT.

### AC-06-11
Mesmo MessageSid não gera efeitos duplicados.

### AC-06-12
Processamento assíncrono não exige Kafka/RabbitMQ/Redis.

### AC-06-13
Retry é limitado e apenas transitório.

### AC-06-14
Arquivo temporário é removido após processamento quando aplicável.

### AC-06-15
Nenhum secret ou binary é logado.

### AC-06-16
Testes normais não chamam Gemini real nem Twilio real.

## 30. Definition of Done

A Fase 06 está pronta quando:
- áudio chega pelo WhatsApp;
- attachment é rastreável;
- download é seguro;
- Gemini transcreve via adapter;
- transcrição entra no pipeline textual;
- source `WHATSAPP_AUDIO` é preservado;
- retry/idempotência funcionam;
- falhas não criam lançamentos;
- testes passam;
- Sandbox funciona para Pai e Mãe.

## 31. Manual Sandbox Validation

### Pai
Enviar:
```text
Gastei 27 reais no café hoje.
```

Validar transcrição, transaction, source, autoria e resposta.

### Mãe
Enviar:
```text
Quanto gastamos com mercado este mês?
```

Validar consulta no mesmo household.

### Invalid speech
Nenhuma transaction deve ser criada.

### Duplicate
Mesmo MessageSid deve produzir um único efeito.

## 32. Codex instruction

> Implemente `docs/phases/06-audio.md`. Respeite `AGENTS.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md`, ADRs e o pipeline textual existente. Áudio deve ser apenas uma nova origem de texto: Twilio media -> download -> `SpeechToTextProvider` -> transcrição -> `ConversationOrchestrator`. Use `GeminiSpeechToTextProvider`, sem acoplar application/domain ao Gemini. Preserve `source = WHATSAPP_AUDIO`, idempotência por MessageSid e rastreabilidade do attachment. Não implemente TTS, imagem, OCR, Whisper local, Redis, Kafka ou RabbitMQ. Ao finalizar, reporte cada AC como PASS ou FAIL, migrations, jobs/adapters criados, testes executados e validações manuais pendentes.
