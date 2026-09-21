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

Estação atual: **5 — Construção**, aberta em 21/09/2026, **fechada exceto
por um item que só o dono verifica**.

Fechadas:
- 1 Escopo — spec e CONSTRAINTS registrados · evidência: commit `e9a1e94`
- 2 Fronteiras — classificação acima, projeto confirmado, sem consumo de estrutura San & Co. · evidência: esta seção
- 3 Fundação — repositório, árvore, segredos fora do código, CLAUDE.md, RUNBOOK.md, **CI verde num push real** · evidência: [run 35611480514](https://github.com/sancompany/Playlist.MostrAi/actions/runs/35611480514), `conclusion: success`
- 4 Contratos — `docs/funcional.md`, `docs/inventario-de-dados.md`, contrato de API (já consumido do backend, seção 6 do prompt original) · evidência: commit `fe0b995`

5 Construção — quase fechada:
- Escopo da v1 (blocos 1–5 do MVP: boot, playlist em laço, proof-of-play,
  cache de mídia, painel de PIN) implementado · evidência: commits `66cef80`
  a `4e56f94`
- Checklist de fechamento: como o tipo do projeto não é nenhum dos quatro de
  `construir` (`CONSTRAINTS.md`, exceção de classificação), o "o que fecha"
  é o mapa de blocos do MVP acima, não `desenvolvimento-web.md`
- Teste no caminho crítico — 39 testes (`PosicaoNaPlaylistTest`,
  `PlaylistJsonTest`, `PlayedJsonTest`, `ChaveCacheTest`, `ProofOfPlayDbTest`,
  `FilaProofOfPlayTest`) · evidência: CI verde, [run 35612446101](https://github.com/sancompany/Playlist.MostrAi/actions/runs/35612446101)
- Ciclo de revisão (skill `revisar`) — 3 ciclos, 3 achados corrigidos (dois
  de correção alta em `PlayerActivity`/`CacheMidia`, um de correção média em
  `PainelActivity`), terceiro ciclo limpo · evidência: commit `4e56f94`
- Revisão de acompanhamento (sessão separada, 21/09/2026) — achado de
  correção alta: em modo degradado, `atualizarPlaylist` reiniciava a
  exibição em andamento a cada busca periódica (15 em 15 min), porque a
  reancoragem por `itemProgramacaoId` sempre falha nesse modo (o campo não
  existe) e o fallback por tempo sempre devolvia o item 0 (`janelaInicio`
  também não existe). Extraído para `ReposicionamentoPlaylist` (objeto
  puro, mesmo padrão de `PosicaoNaPlaylist`), com ramo explícito pro modo
  degradado (mantém o índice atual, não reinicia). 39 → 47 testes ·
  `docs/erros/2026-09-21-modo-degradado-reiniciava-a-cada-poll.md`.
- Provisionamento sem `adb` — dois caminhos a mais, pedidos pelo dono: build
  embutido (`-PconfigDispositivo`) e arquivo externo (`mostrai-config.json`
  no pendrive, lido em runtime com permissão de armazenamento pedida só
  quando necessário). 47 → 53 testes (`ConfigExternaTest`, 5 casos) ·
  evidência: build local verde, a confirmar no CI.
- Retorno de campo (vídeo do dono, 21/09/2026), pontos 1/2/4/5 — 4 correções:
  (1/4) a TV está montada fisicamente de lado; o Android não sabe disso
  sozinho, então o app compensa em runtime girando o próprio conteúdo
  (`RotacaoTela`, par raiz/rotor, compartilhado entre `PlayerActivity` e
  `PainelActivity`), configurável (0/90/180/270) nos três caminhos de
  provisionamento — a direção certa (90 ou 270) só o dono confirma no
  aparelho real. `PlayerView` trocado de `SurfaceView` para `TextureView`
  (necessário para a rotação, e suspeito de também resolver o vídeo bugado).
  (2) tela institucional trocada de cor chapada por degradê radial. (5) PIN
  do painel deixa de ser fixo em 4 dígitos, aceita de 4 a 6 — validado no
  setter de `ConfigAparelho.pinPainel` (mesma guarda de `rotacaoTela`/
  `margemVmin`: um PIN fora do formato nunca é gravado, porque travaria o
  painel de manutenção para sempre sem nenhuma sequência digitável capaz de
  bater com ele). Ponto 3 (ícone/arte de marca) é do dono. Ponto 6
  (atualização OTA pelo site) ainda sem proposta — decisão arquitetural
  maior, pendente. 53 → 62 testes (`ConfigAparelhoTest`, rotação e PIN;
  `rotacaoTela` em `ConfigExternaTest`) · evidência: build + testes locais
  verdes, a confirmar no CI.
- Access — não se aplica (sem área administrativa web, `CONSTRAINTS.md`)
- **"A versão inicial no ar"** — pendente. Para um app sideloaded isso
  significa instalado e rodando num aparelho real; esta sessão não tem
  hardware Android TV nem emulador viável (`CONSTRAINTS.md`). Único item que
  falta para fechar a estação 5 por completo.

Próxima estação: 6 — Prontidão, pede Opus com esforço alto, e só abre depois
que o dono confirmar o app rodando em aparelho real.

## Mapa de caminhos

- Entrada da aplicação: `app/src/main/java/br/com/mostrai/player/PlayerActivity.kt`
- Rede: `app/src/main/java/br/com/mostrai/player/network/` (`MostraiApi`, `HttpCliente`, `PlaylistJson`, `PlayedJson`)
- Playlist e reposicionamento: `app/src/main/java/br/com/mostrai/player/playlist/`
- Proof-of-play (fila durável): `app/src/main/java/br/com/mostrai/player/proof/`
- Configuração do aparelho: `app/src/main/java/br/com/mostrai/player/config/` (`ConfigAparelho` guarda; `ConfigExterna` lê `mostrai-config.json` do pendrive)
- Painel de manutenção: `app/src/main/java/br/com/mostrai/player/ui/PainelActivity.kt`
- Testes: `app/src/test/java/br/com/mostrai/player/` — `./gradlew testDebugUnitTest`
- Variáveis/segredos: nenhum `.env` — três caminhos de provisionamento, nesta ordem de precedência: build embutido (`-PconfigDispositivo`, README "Gerar um APK já configurado por tela") → arquivo externo (`mostrai-config.json` no pendrive, README "Configurar por um arquivo no pendrive") → extras de Intent por `adb` (sempre sobrescreve, é o caminho de depuração, README "Instalar e provisionar em bancada"). Nenhum dos três versiona segredo — `dispositivos/*.json` e `mostrai-config.json` ficam de fora do Git.

## Conformidade

Violação segue o ciclo da skill `leis`. Não existe estado final fora de
conformidade: ou corrige, ou vira exceção registrada no `CONSTRAINTS.md`.

## Pendências que bloqueiam a esteira

- Verificação "no ar" da estação 5 em hardware real — só o dono faz (ver `docs/pendencias.md`)
- CI (`.github/workflows/ci.yml`) pode precisar ser aplicado manualmente pelo dono se a ferramenta recusar o push do workflow (ver `docs/pendencias.md`)
