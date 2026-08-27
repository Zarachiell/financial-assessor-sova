# ADR 0006 — Gemini como provider exclusivo

## Status

Aceita em 2026-08-27. Substitui a estratégia de seleção e rollback para Groq definida no ADR 0004.

## Contexto

O Sova concluiu a migração do fluxo conversacional para Gemini no Google Cloud. A Fase 6 também precisa interpretar áudio, e as fases seguintes incluirão imagens. Manter dois providers ativos aumenta configuração, testes, superfície de falha e custo de manutenção sem benefício atual para este projeto pessoal.

## Decisão

Gemini passa a ser o único provider externo de IA do Sova para texto, áudio e imagem.

- texto continua usando o adapter `GeminiAiProvider`;
- áudio usa `GeminiSpeechToTextProvider`, atrás do port interno `SpeechToTextProvider`;
- imagem será implementada em sua fase própria usando Gemini, sem antecipar esse fluxo agora;
- autenticação continua via ADC e Service Account do Google Cloud;
- configurações, código operacional e estratégia de rollback para Groq são removidos;
- ports internos permanecem desacoplados do SDK do provider.

## Alternativas consideradas

### Manter Groq para rollback e Whisper

Rejeitada porque duplica caminhos operacionais e contraria a decisão de usar Gemini em todas as modalidades.

### Usar um serviço separado de Speech-to-Text

Rejeitada nesta fase porque adiciona outro provider e mais credenciais, enquanto o modelo Gemini adotado aceita áudio.

### Acoplar application diretamente ao SDK Gemini

Rejeitada porque viola os boundaries internos e dificultaria testes determinísticos.

## Consequências

- configuração e operação ficam mais simples;
- indisponibilidade do Gemini não possui fallback automático para outro provider;
- troca futura de provider continua possível por novos adapters dos ports internos;
- áudio e imagens enviam ao provider apenas o conteúdo necessário para a tarefa atual.
