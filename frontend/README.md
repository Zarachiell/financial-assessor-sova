# Dashboard financeiro do Sova

Interface Next.js em modo escuro para consultar o patrimônio do household, separar dinheiro livre, investimentos e dívidas, acompanhar previsto x realizado e administrar transações e entradas de fluxo de caixa.

## Desenvolvimento

Com a API disponível em `http://localhost:8080`:

```bash
npm ci
npm run dev
```

Abra `http://localhost:3000`. O rewrite de desenvolvimento encaminha `/api/*` para a API local.

## Verificações

```bash
npm run lint
npm run test
npm run build
```

Em produção, o frontend é empacotado no container `sova-web` e servido pelo Caddy no mesmo endereço da API. A autenticação usa cookie `HttpOnly`; nenhuma regra de cálculo financeiro é mantida no navegador.
