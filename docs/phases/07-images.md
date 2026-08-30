# Fase 7 — Imagens

## Estado

Implementada em 30/08/2026.

## Objetivo

Receber imagens pelo WhatsApp, extrair dados financeiros candidatos com Gemini e encaminhá-los ao mesmo fluxo de tools e regras de domínio usado por texto e áudio.

## Entregue

- detecção de JPEG, PNG e WebP no webhook;
- download autenticado na Twilio, com limite de tamanho e validação por assinatura binária;
- jobs PostgreSQL com lease, retentativa e `FOR UPDATE SKIP LOCKED`;
- armazenamento local persistente fora do PostgreSQL, com chave opaca e SHA-256;
- análise estruturada por Gemini para tipo de documento, estabelecimento, valor, data e categoria sugerida;
- confiança global e por campo;
- confirmação obrigatória quando valor, data ou confiança forem insuficientes;
- candidatos persistidos e recuperáveis pela tool `get_image_candidates`;
- criação de transação ou fluxo de caixa apenas por command financeiro validado;
- rastreabilidade entre mensagem, anexo, análise e registro financeiro;
- isolamento por household e testes dos fluxos de maior risco.

## Regra operacional

A imagem nunca persiste uma transação diretamente. O provider produz somente um candidato; o backend valida confiança, autorização e dados de domínio antes de qualquer escrita financeira.

## Critérios de aceite

- imagem válida e segura pode gerar transação ou previsão via tool local;
- imagem de baixa confiança pede confirmação e não grava lançamento;
- conteúdo incompatível com o tipo declarado é rejeitado;
- binário não é armazenado no PostgreSQL;
- falha transitória pode ser retomada sem duplicar processamento;
- uma imagem de outro household não pode ser usada como origem de lançamento.
