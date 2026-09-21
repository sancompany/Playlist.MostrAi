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
  provisionamento. `PlayerView` trocado de `SurfaceView` para `TextureView`
  (necessário para a rotação, e suspeito de também resolver o vídeo
  bugado). (2) tela institucional trocada de cor chapada por degradê
  radial. (5) PIN do painel confirmado em exatamente 4 dígitos (não 4-6 —
  ver lição abaixo). Ponto 3 (ícone/arte de marca) é do dono. Ponto 6
  (atualização OTA pelo site) ainda sem proposta — decisão arquitetural
  maior, pendente. Testes: `ConfigAparelhoTest` (rotação e PIN),
  `rotacaoTela` em `ConfigExternaTest` · evidência: build + testes locais
  verdes, a confirmar no CI.
- Correção de rumo, mesma sessão: o dono descreveu a montagem física
  ("logo virada para a direita, lateral esquerda da TV fica embaixo") —
  geometria consistente com a borda esquerda nativa migrando para baixo,
  ou seja, o painel foi montado fisicamente 90° anti-horário; o app
  compensa girando o conteúdo 90° horário (`rotacaoTela: 90`, o padrão já
  sugerido) — a confirmar visualmente pelo dono no aparelho. Nessa mesma
  mensagem o dono também confirmou que o PIN é para ficar em exatamente 4
  dígitos (não 4-6, como uma leitura anterior do pedido original tinha
  entendido) — revertido de volta ao que a outra sessão concorrente já
  tinha implementado (`TAMANHO_PIN = 4`), com o aviso via `Log.w` mantido.
- Assets de marca do dono (21/09/2026) — ícone/banner real (substitui o
  placeholder gerado), vídeo de abertura (`res/raw/video_abertura.mp4`,
  player próprio, só no boot do processo, nunca ao voltar do painel), e
  três artes institucionais fixas (não provisionado, erro ao carregar,
  carregando) que substituem o degradê programático nesses três casos —
  o degradê continua valendo só para o item institucional que o próprio
  backend manda ("sem programação para esta hora", RN-14, decisão do
  dono: isso é conteúdo da playlist, não uma arte fixa do app).
  `TelaInstitucional`, `EstadoInstitucional` (novo), `PlayerActivity`.
- Revisão do app inteiro (skill `revisar`, pedido do dono, 21/09/2026) — 6
  ciclos, os 3 primeiros com achado, os 3 últimos limpos:
  1. `TelaInstitucional` decodificava o PNG do estado institucional dentro
     de `onDraw` (thread de UI) — movido pra uma thread de fundo, guardado
     por geração (mesmo padrão de `PlayerActivity.geracaoReproducao`).
  2. `CacheMidia.baixarPara`/`HttpCliente.chamar`: `openConnection() as
     HttpURLConnection` lança `ClassCastException` (não `IOException`)
     pra uma URL com esquema inesperado (não http/s) — escapava do catch
     de quem chama e derrubava o app; convertido pra `IOException` na
     origem. `MostraiApi.enviarLote`/`enviarLegado`/`heartbeat` também
     ganharam o mesmo catch amplo que `buscarPlaylist` já tinha (corpo de
     resposta malformado lança `JSONException`, não capturada antes).
     Cobertos por `HttpClienteTest` e `CacheMidiaTest` (novos).
  3. **`PainelActivity` não era translúcida** — achado de correção alta:
     `docs/funcional.md` sempre documentou "o player continua rodando por
     trás" ao abrir o painel, e o fundo semi-opaco (`#E6000000`) só faz
     sentido como sobreposição, mas nenhum tema declarava
     `windowIsTranslucent`. Abrir o painel era uma troca de Activity
     opaca — `PlayerActivity.onStop()` liberava o ExoPlayer e cancelava
     todos os temporizadores, interrompendo uma exibição paga no meio.
     Corrigido com `Theme.MostraiPlayer.Translucido`, só em
     `PainelActivity`. Sem como testar sem aparelho real — nova pendência
     em `docs/pendencias.md`. Detalhe em
     `docs/erros/2026-09-21-painel-parava-o-player-em-vez-de-so-cobrir.md`.
  4. `.gitignore` não cobria `mostrai-config.json` (só `dispositivos/*.json`)
     apesar de `CLAUDE.md` já dizer que os dois ficam fora do Git — corrigido.
  67 testes (62 → 67). Handoff consolidado pro backend em `PARA-O-BACKEND.md`
  (novo).
- Preparo pro backend (21/09/2026) — `margemVmin` virou 4 valores
  independentes por lado (`MargensOverscan`; `RotacaoTela` passou a aplicar
  o padding em `rotor`, o quadro visual, não em `raiz` — só assim margem
  assimétrica sobrevive a `rotacaoTela` de 90°/270°), e o player passou a
  decidir vídeo × tela institucional só pela presença de `url`, não pela
  flag `institucional` — o vídeo de fundo servido pelo backend já toca sem
  outra versão do app. RN-15 e RN-16 em `docs/funcional.md`. 67 → 70 testes.
- Revisão de acompanhamento (foco no caminho de vídeo, pedido do dono,
  21/09/2026) — 4 ciclos, 1 achado de correção **alta**, os 2 últimos
  limpos: `PainelActivity` juntava tema translúcido (correção do ciclo
  anterior) com o `screenOrientation="landscape"` que já tinha no
  manifesto. No Android 8.0 — versão exata do parque instalado, com
  `targetSdk = 26` — essa combinação faz `Activity.onCreate` lançar
  `IllegalStateException("Only fullscreen opaque activities can request
  orientation")`: abrir o painel derrubaria o app inteiro na loja, matando
  o player e a exibição paga em andamento. `screenOrientation` removido
  (janela translúcida herda a orientação da Activity opaca de trás).
  `docs/erros/2026-09-21-painel-translucido-com-orientacao-fixa-derrubava-o-app.md`.
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
- Handoff pro backend (`sancompany/mostrai`): `PARA-O-BACKEND.md` — o que este app já assume do contrato, e o que ainda falta do lado de lá (`margemVmin` por admin/por lado, vídeo de fundo institucional servido pelo backend).

## Conformidade

Violação segue o ciclo da skill `leis`. Não existe estado final fora de
conformidade: ou corrige, ou vira exceção registrada no `CONSTRAINTS.md`.

## Pendências que bloqueiam a esteira

- Verificação "no ar" da estação 5 em hardware real — só o dono faz (ver `docs/pendencias.md`)
- CI (`.github/workflows/ci.yml`) pode precisar ser aplicado manualmente pelo dono se a ferramenta recusar o push do workflow (ver `docs/pendencias.md`)
