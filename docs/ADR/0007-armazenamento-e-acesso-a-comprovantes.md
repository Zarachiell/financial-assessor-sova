# ADR 0007 — Armazenamento e acesso a comprovantes

## Contexto

As fases 7 e 7.5 introduzem imagens financeiras e comprovantes que precisam sobreviver ao processamento assíncrono e ser recuperados posteriormente. Guardar binários no PostgreSQL aumentaria backup, I/O e custo operacional da instância inicial. Expor caminhos permanentes tornaria o acesso difícil de revogar e ampliaria o risco de vazamento entre households.

## Decisão

Os binários serão armazenados por uma port `MediaStorage`, inicialmente implementada em volume local persistente. O PostgreSQL guardará chave de armazenamento, SHA-256, tipo, tamanho e relações de autorização.

O acesso externo a comprovantes ocorrerá por URLs HTTPS temporárias assinadas com HMAC. Antes da resposta, o adapter verificará a assinatura, a expiração e a integridade do arquivo. O vínculo entre comprovante, liquidação e anexo será protegido também por foreign keys compostas com `household_id`.

## Alternativas consideradas

- **BYTEA no PostgreSQL:** simplifica atomicidade, mas aumenta banco, backups e contenção sem benefício suficiente para o volume atual.
- **URL pública permanente:** simples, porém inadequada para dados financeiros privados.
- **Object storage desde o início:** oferece boa durabilidade, mas adiciona credenciais e infraestrutura antes de existir necessidade operacional.

## Consequências

- o deploy precisa montar e incluir o volume de mídia na estratégia de backup;
- integridade e autorização permanecem verificáveis independentemente do provider de IA;
- uma futura migração para object storage exige apenas outro adapter de `MediaStorage`;
- a disponibilidade dos comprovantes acompanha inicialmente a disponibilidade do host único.
