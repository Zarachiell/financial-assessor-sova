# Deploy na VPS

Este documento descreve o deploy do Sova em uma VPS única por GitHub Actions.

## Fluxo

```text
push na main
  -> CI
  -> testes e fat JAR
  -> smoke test com Docker Compose
  -> Deploy production
  -> SCP do commit aprovado
  -> docker compose -f docker-compose.yml -f docker-compose.production.yml up --build --wait
```

O deploy só ocorre quando o workflow `CI` termina com sucesso para um `push` na branch `main`. Também pode ser iniciado manualmente pela interface do GitHub, desde que a branch selecionada seja `main`.

O workflow monta o `.env` de produção usando GitHub Secrets e o envia diretamente para:

```text
/opt/sova/shared/.env
```

Cada execução é extraída em uma pasta imutável sob `/opt/sova/releases/`. Depois que o Docker Compose fica saudável, `/opt/sova/current` passa a apontar para a nova release. O nome explícito do projeto Compose (`sova`) mantém os containers e volumes PostgreSQL entre releases.

## 1. Preparar a VPS

Os comandos abaixo assumem Ubuntu e um usuário administrativo com `sudo`.

Instale Docker Engine com o plugin Docker Compose conforme a documentação oficial do Docker. Depois crie um usuário exclusivo para deploy:

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo install -d -o deploy -g deploy -m 700 /opt/sova
sudo install -d -o deploy -g deploy -m 700 /opt/sova/shared
```

Prepare também o diretório da credencial Google com um grupo dedicado. O processo Java não será executado como root; a imagem fixa sua identidade em `10001:10001` e o Compose adiciona apenas o GID numérico real deste arquivo como grupo suplementar:

```bash
sudo groupadd --system sova-google-credentials
sudo usermod -aG sova-google-credentials deploy
sudo install -d -o root -g sova-google-credentials -m 750 /opt/sova/secrets
sudo chown root:sova-google-credentials /opt/sova/secrets/google-service-account.json
sudo chmod 640 /opt/sova/secrets/google-service-account.json
```

Se o grupo já existir, ignore o erro de `groupadd`. Abra uma nova sessão do usuário `deploy` depois do `usermod`. Não altere o arquivo para `644`: o workflow aceita somente `0640` ou `0440` e descobre o GID pelo `stat`, sem assumir correspondência entre nomes de grupos do host e do container.

O grupo `docker` possui privilégios elevados sobre a máquina. A chave deste usuário deve ser exclusiva do GitHub Actions e não deve ser reutilizada para acesso pessoal.

## 2. Criar a chave de deploy

Na máquina administrativa, crie uma chave sem passphrase dedicada à automação:

```bash
ssh-keygen -t ed25519 -C "github-actions-sova" -f ./sova-github-actions
```

Na VPS, prepare o diretório de chaves:

```bash
sudo install -d -o deploy -g deploy -m 700 /home/deploy/.ssh
sudo touch /home/deploy/.ssh/authorized_keys
sudo chown -R deploy:deploy /home/deploy/.ssh
sudo chmod 700 /home/deploy/.ssh
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

Adicione o conteúdo de `sova-github-actions.pub` em:

```text
/home/deploy/.ssh/authorized_keys
```

## 3. Configurar o `.env` de produção

Cadastre como repository secrets todos os valores abaixo. Não use os valores fictícios de `.env.example`:

```dotenv
DATABASE_NAME=sova
DATABASE_USER=sova
DATABASE_PASSWORD=<senha-forte>

SOVA_HTTP_PORT=80
SOVA_HTTPS_PORT=8443
SOVA_ADDRESS=http://<ip-publico>

TWILIO_ACCOUNT_SID=<account-sid>
TWILIO_AUTH_TOKEN=<auth-token>
TWILIO_WHATSAPP_SENDER=whatsapp:+55<ddd><numero>

SOVA_HOUSEHOLD_NAME=<nome-do-household>
SOVA_USER_A_NAME=<nome>
SOVA_USER_A_WHATSAPP=whatsapp:+55<ddd><numero>
SOVA_USER_B_NAME=<nome>
SOVA_USER_B_WHATSAPP=whatsapp:+55<ddd><numero>

AI_PROVIDER=gemini
GOOGLE_CLOUD_PROJECT=sova-506801
GOOGLE_CLOUD_LOCATION=global
GEMINI_MODEL=gemini-3.5-flash-lite
GEMINI_AUDIO_MODEL=gemini-3.5-flash-lite
MAX_TOOL_ITERATIONS=5
SOVA_MAX_AUDIO_BYTES=16777216
SOVA_MAX_AUDIO_DURATION_MINUTES=5
SOVA_AUDIO_MAX_ATTEMPTS=3
```

Gemini é o único provider operacional. Ele usa ADC pelo arquivo montado em `/run/secrets/google_service_account`, sem API key. `GEMINI_AUDIO_MODEL` pode acompanhar o modelo textual ou ser configurado separadamente.

O workflow cria ou atualiza `/opt/sova/shared/.env` com permissão `600`. Não é necessário criar esse arquivo manualmente.

Para conferir a permissão depois do primeiro deploy:

```bash
stat -c '%U %G %a %n' /opt/sova/shared/.env
stat -c '%U %G %a %n' /opt/sova/secrets/google-service-account.json
```

O endereço HTTP por IP permite a validação inicial do canal real, mas não atende ao requisito de HTTPS definido em `SPEC.md`. Antes de trafegar informações financeiras reais, configure HTTPS.

## 4. Cadastrar o host SSH no GitHub

Obtenha a chave pública do servidor por uma sessão administrativa confiável:

```bash
sudo cat /etc/ssh/ssh_host_ed25519_key.pub
```

Monte uma linha `known_hosts` usando o IP e a chave retornada:

```text
<ip-publico> ssh-ed25519 <chave-publica-do-host>
```

Se o SSH utilizar uma porta diferente de 22, o primeiro campo deve ser `[<ip-publico>]:<porta>`.

No repositório GitHub, abra `Settings -> Secrets and variables -> Actions`.

Crie os repository secrets obrigatórios:

```text
VPS_SSH_PRIVATE_KEY   conteúdo completo do arquivo privado sova-github-actions
VPS_SSH_KNOWN_HOSTS  linha known_hosts criada acima
VPS_HOST         IP público da VPS
VPS_USER         deploy
```

Os secrets abaixo são opcionais; o workflow já utiliza estes valores como padrão:

```text
VPS_PORT         22
VPS_DEPLOY_PATH  /opt/sova
```

Crie ou revise também as repository variables abaixo. Os defaults do workflow são mostrados à direita:

```text
AI_PROVIDER              gemini
GOOGLE_CLOUD_PROJECT     sova-506801
GOOGLE_CLOUD_LOCATION    global
GEMINI_MODEL             gemini-3.5-flash-lite
MAX_TOOL_ITERATIONS      5
SOVA_MAX_PROJECTION_MONTHS 24
```

O JSON da Service Account não é enviado pelo GitHub Actions. Ele permanece exclusivamente em `/opt/sova/secrets/google-service-account.json` na VPS.

Os Secrets são enviados somente ao arquivo de produção e não são incluídos no pacote versionado da aplicação nem impressos pelo workflow.

## 5. Primeiro deploy

Faça push para `main` ou abra `Actions -> Deploy production -> Run workflow` e selecione `main`.

Depois do deploy, valide:

```bash
curl http://<ip-publico>/health
```

Resposta esperada:

```json
{"status":"UP","components":{"database":"UP"}}
```

Na Twilio, configure o webhook inbound como:

```text
POST http://<ip-publico>/webhooks/twilio/whatsapp
```

## Diagnóstico e recuperação

Para inspecionar a release ativa:

```bash
readlink -f /opt/sova/current
cd /opt/sova/current
docker compose ps
docker compose logs --tail=200
```

Para usar o override de produção explicitamente nos diagnósticos:

```bash
export GOOGLE_SERVICE_ACCOUNT_GID="$(stat -c '%g' /opt/sova/secrets/google-service-account.json)"
docker compose -f docker-compose.yml -f docker-compose.production.yml ps
docker compose -f docker-compose.yml -f docker-compose.production.yml logs --tail=200
```

Valide a identidade real e a leitura da credencial sem imprimir seu conteúdo:

```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml exec \
  -e EXPECTED_GOOGLE_SERVICE_ACCOUNT_GID="$GOOGLE_SERVICE_ACCOUNT_GID" \
  sova-api sh -ec '
  test "$(id -u)" = "10001"
  test "$(id -g)" = "10001"
  id -G | tr " " "\n" | grep -Fx "$EXPECTED_GOOGLE_SERVICE_ACCOUNT_GID" >/dev/null
  test -r /run/secrets/google_service_account
  printf "Sova runtime identity: uid=%s gid=%s supplementary_gids=%s; Google credentials: OK\n" \
    "$(id -u)" "$(id -g)" "$(id -G)"
'
```

Depois envie `Oi Sova` por WhatsApp e execute ao menos uma escrita e uma consulta financeira. Confirme nos logs apenas `provider`, `model`, tokens, latência, tools e status; nunca o prompt completo ou a credencial.

Se um deploy falhar, o link `current` continua indicando a última release que chegou ao estado saudável. Para reativá-la, desde que suas migrations sejam compatíveis:

```bash
cd /opt/sova/current
docker compose up --detach --build --remove-orphans --wait
```

Não existe rollback operacional para Groq. Para reativar uma release anterior, ela precisa continuar compatível com o schema aplicado e com a configuração Gemini/ADC vigente.

Migrations Flyway não devem ser revertidas ou editadas. Um rollback de aplicação só é seguro quando a versão anterior permanece compatível com o schema já aplicado.
