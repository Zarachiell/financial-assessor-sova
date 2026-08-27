# Phase 3.5 — Migração do Provider de IA: Groq → Google Gemini

> **Contexto de deploy:** o backend está atualmente em produção via Docker Compose.  
> Não alterar a estratégia de deployment, não migrar para outro runtime e não recriar a infraestrutura.  
> Adaptar a integração Gemini ao Compose existente.

## Objetivo

Migrar o backend do Sova do Groq para o Google Gemini usando a API do Google Cloud (`aiplatform.googleapis.com`), mantendo intactos:

- System Prompt do Sova
- Tool Calling existente
- schemas das tools
- regras financeiras
- fluxo de conversação
- persistência
- autenticação dos usuários
- comportamento atual do backend

A migração deve ser feita com uma camada de provider bem definida, evitando acoplamento direto do restante da aplicação ao SDK do Google.

---

## Contexto da infraestrutura

Projeto Google Cloud:

```text
sova-506801
```

A API `aiplatform.googleapis.com` já está ativada.

Existe uma Service Account exclusiva para o backend do Sova com permissão:

```text
roles/aiplatform.user
```

Na VPS, a credencial está armazenada em:

```text
/opt/sova/secrets/google-service-account.json
```

O arquivo:

- NÃO deve ser copiado para o repositório
- NÃO deve ser commitado
- NÃO deve ser incluído na imagem Docker
- NÃO deve ter seu conteúdo registrado em logs

---

## 1. Auditar a integração atual com Groq

Antes de alterar código, localizar:

- cliente Groq
- configuração do provider
- variáveis de ambiente Groq
- chamadas de chat/completion
- implementação de Tool Calling
- tratamento de mensagens assistant/tool
- schemas enviados ao modelo
- parsing das respostas
- retries
- timeouts
- tratamento de erros
- testes existentes

Documentar brevemente quais arquivos serão modificados.

Não remover a implementação Groq antes de a implementação Gemini estar funcional.

---

## 2. Criar abstração de provider

O restante da aplicação não deve depender diretamente de Groq ou Google.

Criar ou adaptar uma interface equivalente a:

```ts
interface AIProvider {
  generate(...): Promise<AIResponse>;
}
```

ou utilizar a abstração equivalente já existente no projeto.

Devem existir providers separados, por exemplo:

```text
GroqProvider
GeminiProvider
```

A camada de negócio e o sistema de tools devem conversar com essa abstração.

---

## 3. Instalar o SDK oficial do Google

Como o backend é Kotlin/JDK 21, usar o SDK oficial Kotlin:

```kotlin
implementation("com.google.genai:google-genai-kotlin:0.5.0")
```

Não adicionar Node.js nem utilizar SDKs Gemini antigos/depreciados.

---

## 4. Configuração via ambiente

Adicionar suporte às seguintes variáveis:

```env
AI_PROVIDER=gemini

GOOGLE_CLOUD_PROJECT=sova-506801
GOOGLE_CLOUD_LOCATION=global

GEMINI_MODEL=<modelo-configuravel>
```

Dentro do container, a autenticação será apontada para:

```env
GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/google_service_account
```

O modelo NÃO deve ficar hardcoded em vários arquivos.

Centralizar sua definição em configuração.

Caso `GEMINI_MODEL` não esteja configurado, utilizar `gemini-3.5-flash-lite`.

---

## 5. Inicializar Gemini usando Google Cloud

A autenticação deve utilizar Application Default Credentials através da Service Account.

Inicialização Kotlin acordada:

```kotlin
Client(
    project = project,
    location = location,
    enterprise = true,
)
```

Não utilizar API key Gemini.

Não carregar manualmente o conteúdo do JSON da Service Account.

O SDK deve utilizar:

```text
GOOGLE_APPLICATION_CREDENTIALS
```

para resolver a autenticação.

---

## 6. Preservar o System Prompt do Sova

O System Prompt atual deve continuar sendo carregado pelo mecanismo existente.

Não duplicar o prompt dentro do provider.

A personalidade, regras financeiras e comportamento do Sova devem continuar separados da infraestrutura do modelo.

Fluxo esperado:

```text
Prompt Loader
      ↓
System Prompt do Sova
      ↓
AI Provider
      ↓
Gemini
```

---

## 7. Migrar o Tool Calling

Adaptar as tools atuais para o formato esperado pelo SDK do Google.

IMPORTANTE:

A lógica das tools NÃO deve ser reescrita.

Somente adaptar:

```text
schema interno atual
        ↓
adapter
        ↓
formato Gemini
```

Preservar:

- nomes das tools
- descrições
- parâmetros
- validação
- regras de autorização
- execução server-side

O modelo nunca deve executar operações financeiras diretamente.

Fluxo:

```text
Usuário
   ↓
Gemini
   ↓
tool call
   ↓
Backend valida
   ↓
Backend executa tool
   ↓
resultado da tool
   ↓
Gemini
   ↓
resposta final
```

---

## 8. Implementar loop de Tool Calling corretamente

Uma interação pode exigir mais de uma rodada.

Suportar:

```text
Gemini
  ↓
tool_call
  ↓
backend executa
  ↓
tool_result
  ↓
Gemini novamente
```

até existir uma resposta final ou atingir um limite seguro de iterações.

Adicionar limite configurável, por exemplo:

```env
MAX_TOOL_ITERATIONS=5
```

Evitar loops infinitos.

---

## 9. Normalização de respostas

Criar uma representação interna independente de provider para:

- texto
- tool calls
- tool results
- usage/tokens
- finish reason
- erros

Não permitir que estruturas específicas do SDK Google vazem para controllers, services financeiros ou frontend.

---

## 10. Tratamento de erros

Implementar tratamento explícito para:

- autenticação inválida
- Service Account sem permissão
- modelo inexistente
- quota excedida
- rate limit
- timeout
- erro temporário do Google
- resposta inválida
- tool call inválido

Nunca retornar stack trace, credenciais ou dados internos para o frontend.

Registrar erros de forma segura.

---

## 11. Timeout e retries

Adicionar timeout explícito para chamadas ao modelo.

Retries devem ocorrer somente em erros transitórios apropriados.

Não repetir automaticamente operações financeiras executadas por tools.

Em especial, evitar duplicação de:

- criação de despesa
- edição de transação
- exclusão
- qualquer operação mutável

A camada de tool calling deve continuar garantindo idempotência quando aplicável.

---

## 12. Observabilidade de custo

Registrar, quando disponível:

```text
provider
model
input_tokens
output_tokens
total_tokens
latency
tool_calls
status
```

NÃO registrar:

- credenciais
- conteúdo integral de dados financeiros sensíveis
- JSON da Service Account

Esses dados serão usados posteriormente para medir o custo real do Gemini.

---

## 13. Manter fallback temporário para Groq

Durante esta phase, preservar Groq como provider alternativo.

Seleção por variável:

```env
AI_PROVIDER=gemini
```

ou:

```env
AI_PROVIDER=groq
```

Objetivo:

permitir rollback imediato sem alteração de código.

Depois de Gemini ficar estável em produção, Groq poderá ser removido em uma phase posterior.

---

## 14. Segurança da Service Account

Garantir que:

```text
/opt/sova/secrets/google-service-account.json
```

não seja referenciado por caminhos relativos ao repositório.

Adicionar padrões apropriados ao `.gitignore`, sem mover o arquivo para o projeto.

Nunca imprimir:

```text
private_key
private_key_id
client_email + private key
```

em logs.

---

## 15. Docker Compose

O backend do Sova roda via Docker Compose na VPS.

A credencial da Service Account já existe no host em:

```text
/opt/sova/secrets/google-service-account.json
```

Usar preferencialmente Docker Compose secrets para disponibilizar essa credencial ao container.

Configuração conceitual:

```yaml
services:
  <backend-service>:
    secrets:
      - google_service_account

    environment:
      AI_PROVIDER: gemini
      GOOGLE_CLOUD_PROJECT: sova-506801
      GOOGLE_CLOUD_LOCATION: global
      GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/google_service_account
      GEMINI_MODEL: ${GEMINI_MODEL}

secrets:
  google_service_account:
    file: /opt/sova/secrets/google-service-account.json
```

Requisitos:

- detectar o nome real do serviço backend no Compose atual
- preservar todas as configurações existentes
- não copiar a credencial para a imagem
- não colocar a credencial no repositório
- não colocar o conteúdo JSON no `.env`
- não criar uma segunda cópia da chave
- montar/disponibilizar a credencial somente ao serviço que precisa do Gemini
- manter o processo Java como UID/GID `10001:10001`, conforme o Dockerfile
- descobrir o GID numérico real do arquivo no host e adicioná-lo como grupo suplementar no container
- exigir permissões `0640` ou `0440`, sem leitura para `others`
- validar que o processo dentro do container consegue ler o secret
- não expor o conteúdo do secret em logs

Após subir o container, validar com comando equivalente a:

```bash
export GOOGLE_SERVICE_ACCOUNT_GID="$(stat -c '%g' /opt/sova/secrets/google-service-account.json)"
docker compose exec \
  -e EXPECTED_GOOGLE_SERVICE_ACCOUNT_GID="$GOOGLE_SERVICE_ACCOUNT_GID" \
  <backend-service> sh -ec '
  test "$(id -u)" = "10001"
  test "$(id -g)" = "10001"
  id -G | tr " " "\n" | grep -Fx "$EXPECTED_GOOGLE_SERVICE_ACCOUNT_GID" >/dev/null
  test -r /run/secrets/google_service_account
  echo "Google credentials: OK"
'
```

Depois executar um smoke test real contra o Gemini.

---

## 16. Testes

Adicionar testes para no mínimo:

### Conversação simples

```text
Oi Sova
```

Deve gerar resposta sem tool call.

### Cadastro de despesa

```text
Gastei 42 reais no almoço hoje
```

Deve identificar corretamente a tool correspondente.

### Consulta financeira

```text
Quanto gastamos com alimentação este mês?
```

Deve utilizar as tools de consulta existentes.

### Tool result

Garantir que o resultado retornado pela tool seja reenviado corretamente ao Gemini.

### Falha de tool

Garantir resposta segura quando a execução falhar.

### Loop

Garantir que exista limite de iterações.

### Provider switch

Testar:

```env
AI_PROVIDER=gemini
```

e, enquanto Groq permanecer disponível:

```env
AI_PROVIDER=groq
```

---

## 17. Smoke test na VPS

Depois do deploy, testar como o mesmo usuário que executa a aplicação:

```text
deploy
```

Confirmar que ele consegue acessar a credencial via Docker Compose secret.

Validar dentro do container:

```bash
export GOOGLE_SERVICE_ACCOUNT_GID="$(stat -c '%g' /opt/sova/secrets/google-service-account.json)"
docker compose exec \
  -e EXPECTED_GOOGLE_SERVICE_ACCOUNT_GID="$GOOGLE_SERVICE_ACCOUNT_GID" \
  <backend-service> sh -ec '
  test "$(id -u)" = "10001"
  test "$(id -g)" = "10001"
  id -G | tr " " "\n" | grep -Fx "$EXPECTED_GOOGLE_SERVICE_ACCOUNT_GID" >/dev/null
  test -r /run/secrets/google_service_account
  echo "Google credentials: OK"
'
```

Sem imprimir o conteúdo do arquivo.

Depois executar uma chamada mínima ao Gemini.

Confirmar:

```text
HTTP/API OK
autenticação OK
modelo OK
resposta textual OK
tool calling OK
```

---

## 18. Critérios de conclusão

A Phase 3.5 só está concluída quando:

- [ ] Gemini responde pelo backend
- [ ] autenticação utiliza Service Account
- [ ] nenhuma API key Gemini é necessária
- [ ] System Prompt atual continua funcionando
- [ ] personalidade do Sova foi preservada
- [ ] Tool Calling funciona
- [ ] tools financeiras existentes não foram reescritas desnecessariamente
- [ ] tool results retornam corretamente ao modelo
- [ ] existe limite de loop de tools
- [ ] erros são tratados adequadamente
- [ ] modelo é configurável por env
- [ ] provider é configurável por env
- [ ] Groq continua disponível temporariamente para rollback
- [ ] credencial não está no Git
- [ ] credencial não está na imagem Docker
- [ ] execução funciona na VPS via Docker Compose
- [ ] testes existentes continuam passando
- [ ] novos testes do Gemini passam
- [ ] nenhuma regressão funcional foi introduzida

---

## Restrições

Não:

- reestruturar módulos financeiros sem necessidade
- alterar banco de dados
- alterar schemas das entidades financeiras
- modificar frontend sem necessidade
- refazer autenticação da aplicação
- alterar o System Prompt além do necessário para compatibilidade
- remover Groq antes da validação completa
- expor credenciais
- criar uma API key Gemini
- hardcodar Project ID, localização ou modelo em vários pontos
- alterar a estratégia atual de Docker Compose

Priorizar uma mudança pequena, reversível e testável.

---

## Entrega esperada do Codex

Ao terminar, apresentar:

1. resumo da implementação;
2. arquivos criados;
3. arquivos modificados;
4. dependências adicionadas/removidas;
5. novas variáveis de ambiente;
6. mudanças necessárias no deployment;
7. testes executados e resultados;
8. comando usado para smoke test;
9. eventuais pendências;
10. instruções exatas para rollback para Groq.

Não realizar mudanças adicionais fora do escopo desta Phase sem justificar antes.
