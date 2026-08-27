# ADR 0004 — Gemini via Google Cloud com fallback Groq

## Status

Accepted

## Contexto

O provider Groq atingiu limitações operacionais do free tier. O Sova já possui `AiProvider`, local tool calling e regras financeiras independentes do provider, mas o wiring e a configuração de produção selecionavam exclusivamente Groq.

O backend roda em Kotlin/JDK 21 numa VPS via Docker Compose. Uma Service Account exclusiva, com `roles/aiplatform.user`, já existe e sua credencial está fora do repositório em `/opt/sova/secrets/google-service-account.json`.

## Decisão

O provider principal passa a ser `GeminiAiProvider`, usando o SDK oficial Kotlin e o backend Google Cloud / Agent Platform explicitamente:

```text
enterprise = true
project = GOOGLE_CLOUD_PROJECT
location = GOOGLE_CLOUD_LOCATION
```

A autenticação usa exclusivamente Application Default Credentials. O container recebe `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/google_service_account`; o código não carrega manualmente nem registra o conteúdo do JSON.

O SDK converte somente na camada `infrastructure`:

```text
AiRequest
  -> Gemini Content / FunctionDeclaration
  -> Google Cloud
  -> AiResponse / AiToolCall / AiResponseMetadata
```

As tools continuam sendo executadas localmente pelo dispatcher. Automatic Function Calling do SDK não é utilizado.

Groq permanece selecionável por ambiente durante a estabilização para permitir rollback sem alteração de código.

O secret é adicionado por um override Compose exclusivo de produção. O `sova-api` continua executando como UID/GID `10001:10001`; o GID real do arquivo no host é adicionado como grupo suplementar e validado antes da ativação da release.

## Alternativas consideradas

### API key Gemini

Rejeitada. A Service Account e ADC fornecem identidade explícita, rotação separada e controle IAM por projeto.

### SDK Node `@google/genai`

Rejeitado porque exigiria outro runtime. O SDK oficial Kotlin integra-se ao backend atual sem alterar Ktor, JDK ou a imagem de produção.

### REST manual com Ktor Client

Rejeitado. Exigiria implementar obtenção e renovação de tokens ADC, além de manter manualmente os contratos atuais da API.

### Executar o backend como root para ler o secret

Rejeitado. O processo permanece como UID/GID `10001:10001`; somente o grupo numérico estritamente necessário é acrescentado no container.

## Consequências

### Positivas

- provider principal reversível por configuração;
- credencial ausente do Git e da imagem;
- autenticação sem API key;
- domínio, prompts e tools permanecem independentes do Google;
- métricas de tokens e finish reason ficam normalizadas;
- runtime e estratégia de deployment permanecem inalterados.

### Negativas

- o SDK oficial Kotlin adiciona uma dependência relevante ao fat JAR;
- a VPS precisa manter permissões de grupo restritas e compatíveis com o secret;
- Groq e suas configurações precisam permanecer disponíveis durante a janela de rollback.
