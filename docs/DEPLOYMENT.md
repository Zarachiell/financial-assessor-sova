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
  -> docker compose up --build --wait
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

SOVA_AI_PROVIDER=groq
GROQ_API_KEY=<api-key-groq>
GROQ_MODEL=llama-3.3-70b-versatile
```

`GROQ_API_KEY` deve conter somente a chave, sem prefixo `Bearer`. O modelo é configurável, mas precisa suportar local tool calling.

O workflow cria ou atualiza `/opt/sova/shared/.env` com permissão `600`. Não é necessário criar esse arquivo manualmente.

Para conferir a permissão depois do primeiro deploy:

```bash
stat -c '%U %G %a %n' /opt/sova/shared/.env
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

Se um deploy falhar, o link `current` continua indicando a última release que chegou ao estado saudável. Para reativá-la, desde que suas migrations sejam compatíveis:

```bash
cd /opt/sova/current
docker compose up --detach --build --remove-orphans --wait
```

Migrations Flyway não devem ser revertidas ou editadas. Um rollback de aplicação só é seguro quando a versão anterior permanece compatível com o schema já aplicado.
