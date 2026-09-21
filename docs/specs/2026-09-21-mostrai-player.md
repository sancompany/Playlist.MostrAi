# Mostraí Player — escopo validado

Registro retroativo da estação 1. O escopo deste projeto foi decidido **antes**
desta sessão — co-projetado pelo dono (Bruno), o ChatGPT e outra sessão do
Claude Code — e chegou aqui já fechado, no prompt de abertura. Esta estação
não reabre nenhuma decisão: registra o que já existe, com a evidência de onde
se confere no código.

## Fase 0 — Escopo

**Problema real, e para quem.** O Mostraí é uma rede de mídia digital fora de
casa (DOOH) em Matão-SP: comerciantes cedem espaço, TVs instaladas nesses
comércios exibem anúncios de empresas que pagam por plano mensal. O backend
(`sancompany/mostrai`) monta a playlist de cada tela hora a hora e precisa
guardar o comprovante de que cada anúncio realmente passou — é esse
comprovante que sustenta a cobrança dos anunciantes. **Para quem**: o dono da
operação (Bruno), que precisa provar exibição para cobrar; indiretamente, os
anunciantes que pagam pelo plano.

**O que acontece hoje sem isso.** A TV roda uma página web em quiosque. Ela
funciona, mas o comprovante de exibição é frágil: o `played` dispara logo
depois do `play()` (não no fim do vídeo), a resposta não é lida, não há
retentativa nem identidade de execução — retentativa e duplicata são
indistinguíveis (seção 5 do prompt original, auditoria de 20/09/2026
confirmada no código). É dor real, não pequena: é a base de uma cobrança.

**Já existe pronto?** Não há solução de mercado que resolva proof-of-play
verificável em quiosque Android TV com o hardware específico (SEMP TCL
32S6500S, Android 8) sem depender de serviço de terceiro pago por tela.
Construir vence adotar porque o comprovante é o núcleo do negócio, não uma
funcionalidade genérica.

**Quantos usuários.** Não é contagem de pessoa: é contagem de tela. O
dimensionamento real (quantas telas em 3 meses/1 ano/3 anos) é do dono da
operação comercial, não desta sessão — não instrumentado aqui.

**Perfis de acesso.** Um só, operacional: quem tem o controle remoto da TV e
o PIN do painel de manutenção. Não há login, não há conta de usuário — a
tela se autentica por uma chave de aparelho revogável (veto formal, seção 2
do prompt original).

**Por que agora.** O player web já tem falha de comprovante identificada e
auditada; adiar mantém a cobrança apoiada num comprovante frágil.

**Métrica de sucesso.** Proporção de exibições com `STATE_ENDED` observado
que geram um evento de proof-of-play `contabilizado` (ou `duplicado`, que
também é sucesso) no servidor, sem intervenção manual. O evento que alimenta
essa métrica é o resultado por `execucaoId` que `/played` devolve (seção 6.3
do contrato) — ver `docs/funcional.md`, seção 9.

**Fora de escopo na v1** (vira `CONSTRAINTS.md`, não repetido aqui):
relatório na TV, múltiplas orientações exóticas, telemetria rica além do
proof-of-play, atualização remota (OTA), login de usuário.

## Fase 0.5 — Um projeto ou vários?

Um projeto só. O app consome um contrato do backend (`sancompany/mostrai`),
mas não compartilha usuário, banco nem motivo de existir com ele — é
consumidor de uma API, não parte do mesmo sistema. Ver estação 2.

## Fase 1 — Classificação

| Eixo | Valor |
|---|---|
| Porte | Produto externo/cliente — serve a operação comercial do Mostraí, sustenta cobrança de terceiro (anunciante) |
| Dado sensível | Nenhum — não há dado pessoal de visitante nem de anunciante processado no aparelho (ver `docs/inventario-de-dados.md`) |
| Vida útil | Nasce para durar anos — rede de anúncio em operação contínua |
| Tipo | **Nenhum dos quatro listados na skill `classificar`/`construir` se aplica.** É um app Android TV nativo, sideload, sem interface web — não Institucional, SaaS, E-commerce nem PWA. A estação 5 usa como referência equivalente o mapa de blocos MVP da seção 3 deste documento, não `desenvolvimento-web.md`/`tipo-*.md`. Registrado como exceção em `CONSTRAINTS.md`. |

Os três primeiros eixos apontam para rigor **médio**: produto de negócio real
(não descartável), mas sem dado pessoal, sem pagamento no próprio app, sem
múltiplos perfis de acesso.

## Fase 2 — Validação técnica

- **Depende de outro projeto do ecossistema?** Sim: consome a API do backend
  `sancompany/mostrai` (contrato documentado na seção 6 do prompt original,
  replicado em `docs/funcional.md`). Não compartilha banco nem é chamado por
  ele — consumo unidirecional por HTTP com chave própria (`X-Aparelho-Id`).
- **Onde roda, e por quê.** Hardware físico (SEMP TCL 32S6500S, Android TV 8,
  API 26), por sideload via pendrive. Não é decisão entre opções — é o piso
  real do parque instalado, dado no prompt original. Não roda em nenhuma
  estrutura San & Co. (não há Northflank/Supabase/Cloudflare envolvidos: é
  aparelho de comércio, não serviço hospedado).
- **Stack, e por quê.** Kotlin + Media3 (ExoPlayer) nativo — decisão fechada
  no prompt original (seção 4, decisão 1): o caso de uso é MP4 mudo em tela
  cheia em laço, e o controle sobre estado de reprodução é o que o
  comprovante precisa. WebView e híbrido foram descartados explicitamente.
- **Mais de uma pessoa vai mexer no código?** Não previsto — projeto de
  dono único, sessões de IA diferentes contribuindo ao longo do tempo.

## Fase 3 — Contrapontos

- **Necessidade.** Sem construir, a cobrança de anunciante continua apoiada
  num comprovante que a própria auditoria do dono classificou como frágil.
  Não existe ferramenta pronta que resolva 80% disso para este hardware
  específico sem reescrever a lógica de proof-of-play de qualquer forma.
- **Manutenção.** Quem mantém em 1 ano é o dono, com sessões de IA. Não
  adiciona domínio nem serviço pago novo — roda embarcado no aparelho, sem
  hospedagem própria.
- **Escala.** Escala por tela, não por usuário simultâneo — cada aparelho é
  independente, sem ponto único de falha entre telas (a fila de proof-of-play
  é local a cada aparelho). Servidor central (backend) é quem escala com o
  número de telas, fora do escopo deste repositório.
- **Segurança.** Se a chave de um aparelho vazasse, o pior caso é falsificar
  proof-of-play de uma tela — mitigado por a chave ser revogável no admin. Não
  há usuário/senha, não há dado pessoal a vazar deste app.
- **Sobreposição.** Não duplica nada do ecossistema San & Co. — é específico
  do Mostraí, que é o próprio produto, não uma peça de estrutura.
- **Prazo.** Não há data imóvel formal; a urgência vem da fragilidade do
  comprovante atual, não de um evento com data fixa.

**Veredito:** sobrevive como está. O escopo é proporcional ao problema real
(comprovante frágil), não há alternativa pronta para o hardware-alvo, e as
decisões de arquitetura (Media3 nativo, UUID de execução, fila durável) já
foram testadas contra os contrapontos antes de chegar a esta sessão.

## Fase 3.5 — Autorrevisão

Sem placeholder (`TBD`) pendente nas decisões fechadas. Uma ambiguidade real
identificada e resolvida nesta sessão: retomada de índice após reinício
(item 7.1 do prompt original) — fechada com o GPT em 21/09/2026, registrada
na decisão 5 do `CLAUDE.md` e em `PosicaoNaPlaylist.kt`. Nenhum vazamento de
escopo identificado: cache de mídia (bloco 4 do MVP) estava listado como
prioridade da v1 desde o início e é construído nesta mesma estação 5, não
depois dela.

## Fechamento

Este arquivo e `CONSTRAINTS.md` fecham a estação 1. Próxima: estação 2
(Fronteiras, skill `classificar`).
