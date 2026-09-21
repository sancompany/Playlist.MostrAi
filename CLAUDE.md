# Mostraí Player

Projeto da San & Co. Segue as leis do plugin `san-co`.

## Antes de propor ou escrever qualquer coisa, leia
- `CONSTRAINTS.md` — o que este projeto NÃO faz, e os limites assumidos
- `docs/specs/2026-09-21-mostrai-player.md` — por que existe, e o veredito dos contrapontos
- `docs/funcional.md` — o que o sistema faz, tela por tela
- `docs/erros/` — o que já deu errado aqui; não repita
- `docs/pendencias.md` — o trabalho que falta, e o que só o dono faz
- `RUNBOOK.md` — como operar, reverter e restaurar
- `README.md` — como rodar e testar

## Classificação (estação 2)

**Estrutura ou projeto — os quatro testes:**

1. Desligamento: só este app para. Nenhum outro projeto depende dele. → **projeto**.
2. Público: tem usuário final próprio (quem opera o painel de manutenção, quem depende do comprovante para cobrar). → **projeto**.
3. Entrega: entregável isolado — não depende de nenhuma peça de estrutura San & Co. para funcionar. → **projeto**.
4. Pedido de mudança: só o dono do Mostraí pede alteração. → **projeto**.

Os quatro apontam para **projeto**, sem ambiguidade.

**O que consome de plataforma:** nada de estrutura San & Co. (sem Checkout —
não há pagamento no app; sem Cloudflare Access — não há área administrativa
web, o painel é on-device com PIN local; sem Google Workspace/Drive). O único
consumo externo é a **API do backend `sancompany/mostrai`** (projeto
irmão, não estrutura — contrato em `docs/funcional.md`, seção "Contrato com
o backend").

**Onde roda:** hardware físico (SEMP TCL 32S6500S, Android TV 8), sideload
por pendrive. Não é hospedagem escolhida entre opções — é o parque instalado
real do Mostraí.

**Banco próprio:** SQLite local a cada aparelho (fila de proof-of-play,
`ProofOfPlayDb`), embarcado no APK — não é instância de banco hospedada,
não há banco compartilhado com o backend nem com outro projeto.

Porte: Produto externo/cliente · Dado sensível: nenhum · Vida útil: nasce
para durar anos → rigor médio (ver `docs/specs/`, Fase 1).

## Estado na esteira

Estação atual: **5 — Construção**, aberta em 21/09/2026.

Fechadas:
- 1 Escopo — spec e CONSTRAINTS registrados · evidência: commit `e9a1e94`
- 2 Fronteiras — classificação acima, projeto confirmado, sem consumo de estrutura San & Co. · evidência: esta seção
- 3 Fundação — repositório, árvore, segredos fora do código, CLAUDE.md, RUNBOOK.md, CI · evidência: commits desta sessão, PR [#1](https://github.com/sancompany/Playlist.MostrAi/pull/1)
- 4 Contratos — `docs/funcional.md`, `docs/inventario-de-dados.md`, contrato de API (já consumido do backend, seção 6 do prompt original) · evidência: commits desta sessão

Falta para fechar a 5: cache de mídia (bloco 4 do MVP) construído e testado;
"a versão inicial no ar" — que para um app sideloaded significa instalado e
rodando num aparelho real, verificação que só o dono pode fazer (esta sessão
não tem hardware Android TV nem emulador viável — ver `CONSTRAINTS.md`).

Próxima estação: 6 — Prontidão, pede Opus com esforço alto, e só abre depois
que o dono confirmar o app rodando em aparelho real.

## Mapa de caminhos

- Entrada da aplicação: `app/src/main/java/br/com/mostrai/player/PlayerActivity.kt`
- Rede: `app/src/main/java/br/com/mostrai/player/network/` (`MostraiApi`, `HttpCliente`, `PlaylistJson`, `PlayedJson`)
- Playlist e reposicionamento: `app/src/main/java/br/com/mostrai/player/playlist/`
- Proof-of-play (fila durável): `app/src/main/java/br/com/mostrai/player/proof/`
- Configuração do aparelho: `app/src/main/java/br/com/mostrai/player/config/ConfigAparelho.kt`
- Painel de manutenção: `app/src/main/java/br/com/mostrai/player/ui/PainelActivity.kt`
- Testes: `app/src/test/java/br/com/mostrai/player/` — `./gradlew testDebugUnitTest`
- Variáveis/segredos: nenhum `.env` — configuração do aparelho fica em `SharedPreferences` no próprio dispositivo, provisionada por extras de Intent (`README.md`, "Instalar e provisionar em bancada")

## Conformidade

Violação segue o ciclo da skill `leis`. Não existe estado final fora de
conformidade: ou corrige, ou vira exceção registrada no `CONSTRAINTS.md`.

## Pendências que bloqueiam a esteira

- Verificação "no ar" da estação 5 em hardware real — só o dono faz (ver `docs/pendencias.md`)
- CI (`.github/workflows/ci.yml`) pode precisar ser aplicado manualmente pelo dono se a ferramenta recusar o push do workflow (ver `docs/pendencias.md`)
