# Definição funcional — Mostraí Player

O que o sistema faz. Adaptado de um app web para um app Android TV nativo:
não há URL nem tela no sentido de página — "tela" aqui é estado visual do
único Activity principal (`PlayerActivity`) mais o painel de manutenção
(`PainelActivity`).

## 1. Público-alvo

- **Espectador do comércio** — não interage com o app, só assiste. Quer ver
  vídeo em laço sem interrupção, sem barra de sistema, sem cursor. Não sabe
  nem precisa saber que o app existe.
- **Operador de manutenção** (funcionário do comércio ou técnico do
  Mostraí) — abre o painel para checar se a tela está funcionando ou para
  trocar a margem de overscan. Sabe usar um controle remoto de TV; não
  necessariamente sabe o que é um PIN de 4 dígitos até alguém explicar.
- **Backend `sancompany/mostrai`** — não é papel humano, mas é quem manda:
  decide playlist, janela, e o que conta como comprovante. O app nunca age
  sem ele (ou sem a última resposta dele em cache).

## 2. Jornada principal

**Espectador (implícita — o app roda sozinho):**
1. TV liga → app sobe automaticamente (`BootReceiver`), sem interação.
2. App busca a playlist do servidor (ou usa a última em cache).
3. App toca os itens em laço, tela cheia, mudo.
4. Cada exibição completa (`STATE_ENDED`) vira um evento na fila de
   proof-of-play, enviado ao servidor em segundo plano.

*Isso é tudo que essa pessoa precisa fazer* — nenhuma jornada secundária,
não há interação nenhuma prevista para o espectador.

**Operador de manutenção:**
1. Aperta OK/CENTER 3 vezes em até 3 segundos no controle remoto.
2. Digita o PIN de 4 dígitos na grade numérica na tela (D-pad).
3. PIN certo → vê tela e chave configuradas, modo de contrato, origem da
   última playlist, erro do aparelho (se houver), fila de proof-of-play
   (pendentes/perdas).
4. PIN errado → mensagem de erro, campo limpo, tenta de novo.
5. `VOLTAR` → fecha o painel, volta à reprodução normal sem interromper o
   vídeo em andamento (o player continua rodando por trás).

## 3. Telas

| Tela | Quem acessa | O que mostra | O que dá pra fazer | Para onde leva |
|---|---|---|---|---|
| Player (tela cheia) | Espectador (passivo) | Vídeo do anunciante em laço, ou peça institucional desenhada no aparelho | Nada (sem interação prevista) | Painel, via gesto |
| Painel — PIN | Operador | Teclado numérico 0–9, máscara do PIN digitado | Digitar PIN | Painel — informações (PIN certo) ou continua aqui (PIN errado) |
| Painel — informações | Operador | Tela, chave (truncada), servidor, provisionado, margem, atraso da virada, modo de contrato, origem da playlist, erro do aparelho, fila de proof-of-play | Ler (somente leitura na v1) | Player, via `VOLTAR` |

Lista fechada: as três telas cobrem as duas jornadas acima, nenhuma sobra.

## 4. Estados de cada tela

**Player:**
- Vazio (playlist sem itens): não se aplica — `Playlist.somenteInstitucional()`
  garante que sempre há pelo menos um item institucional.
- Carregando: enquanto a primeira playlist não chega, mostra o item
  institucional (com legenda "aparelho ainda não provisionado" ou
  "sem programação para esta hora", conforme o caso).
- Erro (rede caiu, aparelho sem chave, servidor rejeitou): cai para a
  playlist em cache; sem cache, cai para a tela institucional — nunca tela
  preta, nunca crash visível.
- Sucesso: vídeo tocando, tela cheia.
- Sem permissão: não se aplica — não há controle de acesso na tela do
  player, é sempre visível (é uma TV pública).
- Lista longa demais: não se aplica — a playlist é a de uma hora, tamanho
  controlado pelo servidor.

**Painel — PIN:**
- Vazio: máscara mostra os 4 espaços vazios (`· · · ·`) ao abrir.
- Carregando: não se aplica — leitura local, sem rede.
- Erro: PIN incorreto → mensagem vermelha, campo limpo, foco na primeira
  tecla.
- Sucesso: PIN correto → transição para "informações".
- Sem permissão: não se aplica — o próprio PIN é o controle de acesso.
- Lista longa demais: não se aplica.

**Painel — informações:**
- Vazio: aparelho não provisionado → campos mostram "—" em vez de string
  vazia ou `null`.
- Carregando: não se aplica — leitura local (SQLite + SharedPreferences),
  sem chamada de rede ao abrir.
- Erro: não se aplica como estado de tela — erros de rede aparecem como
  **conteúdo** informativo ("Erro do aparelho: HTTP 401"), não como falha da
  tela em si.
- Sucesso: informações completas.
- Sem permissão: não se aplica — só chega aqui quem digitou o PIN certo.
- Lista longa demais: não se aplica — quantidade de campos é fixa.

## 5. Regras de negócio

- **RN-01 — Só `STATE_ENDED` conta.** Uma exibição vira linha elegível para
  envio de proof-of-play somente quando o ExoPlayer atinge `STATE_ENDED`
  daquele item. Violada (erro de reprodução, item trocado no meio): a linha
  é descartada sem contar como perda — nunca é enviada como comprovante.
  Consequência visível: nenhuma (é o comportamento correto, silencioso por
  natureza). `PlayerActivity.onPlayerError`, `FilaProofOfPlay.registrarFalha`.

- **RN-02 — `execucaoId` nasce antes do `play()`.** A linha na fila é
  persistida em SQLite antes de `exo.prepare()`/`playWhenReady = true`.
  Violada: impossível por construção (`mostrarVideo` só chama `prepare()`
  depois que a corrotina de `registrarInicio` retorna). `PlayerActivity.mostrarVideo`.

- **RN-03 — Item institucional e autoanúncio nunca contam.** `contabiliza`
  vem do backend (contrato novo) ou é derivado (`!institucional && !autoanuncio`,
  contrato antigo). Violada: não se aplica — `registrarInicio` retorna
  `null` e nunca cria linha para esses itens; o operador nunca vê esses
  itens na fila do painel. `FilaProofOfPlay.registrarInicio`, `PlaylistJson`.

- **RN-04 — Reentrada é sempre por posição temporal, nunca por índice
  salvo.** Depois de um começo frio ou troca de janela, o índice inicial é
  calculado por `PosicaoNaPlaylist.calcular`, nunca por um índice persistido
  entre sessões. Violada: não se aplica — não existe persistência de índice
  no código, só de `execucaoId`s na fila. Quem vê: ninguém diretamente — é
  comportamento interno; o efeito observável é que o app nunca fica preso
  tocando sempre o início da lista depois de reiniciar. `PlayerActivity.calcularIndiceInicial`.

- **RN-05 — Item pego no meio é pulado, nunca há seek.** Ao reposicionar,
  se o instante calculado cai no meio de um item (além da tolerância de
  500ms), o app pula esse item inteiro e começa o próximo do zero. Violada:
  não se aplica — é regra pura, coberta por teste (`PosicaoNaPlaylistTest`).

- **RN-06 — Sem `servidorAgora` confiável, não retoma posição nenhuma.**
  Detectado por `RelogioJanela.valida()` (relógio monotônico não pode
  "andar para trás" em relação à âncora — sinal de reboot real). Violada:
  cai para índice 0 (`calcularIndiceInicial` retorna 0 sem âncora válida) —
  nunca inventa uma posição. Quem vê: o espectador vê a playlist recomeçar
  do início após um reboot sem sincronização; não há mensagem específica.

- **RN-07 — Proof-of-play só sai da fila em três casos** (seção 6.5 do
  contrato): status definitivo do servidor, `400` de payload malformado, ou
  expiração local de 7 dias. Nunca por timeout, `5xx`, erro de socket ou
  reinício do app. Violada: não se aplica por construção — `tentarEnviar()`
  só chama `db.remover`/`removerLote` nesses três casos.
  `FilaProofOfPlay.tentarEnviar`.

- **RN-08 — Fila limitada a 5.000 linhas.** Estourado, descarta a mais
  antiga e incrementa o contador de perdas. Quem vê: operador, no painel
  ("Eventos perdidos"). `FilaProofOfPlay.limitarTamanho`.

- **RN-09 — Reancoragem por `itemProgramacaoId`, não por índice de array,**
  ao atualizar a playlist dentro da mesma janela. Violada: não se aplica —
  é o comportamento implementado; evita o bug do player web (seção 5 do
  prompt original) em que um item que sai da elegibilidade desloca o índice
  de quem ficou. `PlayerActivity.atualizarPlaylist`.

- **RN-10 — Configuração embutida no build só se aplica se o aparelho ainda
  não estiver provisionado.** `-PconfigDispositivo=<arquivo>.json` (README,
  "Gerar um APK já configurado por tela") nunca sobrescreve um
  provisionamento já existente — nem o de uma instalação anterior, nem o
  que o provisionamento de bancada por `adb` aplicar depois (esse último
  sempre sobrescreve, é o caminho de depuração). Quem vê: ninguém
  diretamente — é o que faz o app subir sozinho no primeiro boot quando o
  APK já veio configurado, sem tela de erro nem intervenção.
  `ConfigAparelho.aplicarConfiguracaoEmbutidaSeNecessaria`.

- **RN-11 — Configuração por arquivo externo (`mostrai-config.json`) só é
  tentada se ainda não houver configuração embutida nem provisionamento
  prévio.** README, "Configurar por um arquivo no pendrive". Pede
  permissão de armazenamento em runtime só quando vai precisar dela (nunca
  antes) — negada, ou sem ninguém pra conceder no primeiro boot, o app
  segue sem travar, sem provisionar, mostrando a tela institucional. Quem
  vê: o operador, no diálogo de permissão do próprio Android (não é tela
  deste app). `ConfigExterna.procurarEAplicar`,
  `PlayerActivity.pedirPermissaoOuAplicarConfigExterna`.

## 6. Textos que o sistema diz

| Texto | Onde | Arquivo |
|---|---|---|
| "Mostraí" (marca, institucional) | Tela institucional | `TelaInstitucional.kt` |
| "Aparelho ainda não provisionado" | Institucional, antes do 1º provisionamento | `strings.xml` |
| "Sem programação para esta hora" | Institucional, provisionado mas sem itens | `strings.xml` |
| "Painel de manutenção" | Título do painel | `strings.xml` |
| "PIN incorreto" | Erro de PIN | `strings.xml` |
| "Pressione VOLTAR para sair" | Dica no painel | `strings.xml` |
| "ATENÇÃO: PIN ainda é o provisório de fábrica." | Aviso no painel, PIN nunca trocado | `PainelActivity.kt` |

## 7. Quando dá errado

- **Rede cai no meio de uma exibição em andamento**: a exibição continua
  normalmente (é local, ExoPlayer não depende de rede depois que o vídeo já
  fez buffer) — só a busca da próxima playlist e o envio de proof-of-play
  ficam pendentes, sem afetar o que já está tocando.
- **Servidor não responde ao buscar playlist**: cai para a última cache
  válida (`PlaylistRepositorio.carregarFallback`); sem cache, cai para
  institucional. Nunca crash, nunca tela preta.
- **Envio de proof-of-play falha (timeout, 5xx)**: linha fica na fila,
  backoff exponencial agenda a próxima tentativa (5s a 30min, sem
  desistência). `FilaProofOfPlay.adiarComBackoff`.
- **Chave do aparelho revogada no admin (401/403)**: fila de proof-of-play
  fica intacta (não descarta nada), erro fica visível no painel
  ("Erro do aparelho: HTTP 401/403"). Reprodução continua com a última
  playlist em cache.
- **Reboot real do aparelho** (não só reinício do app): âncora de tempo
  monotônico invalida (`RelogioJanela.valida()` = false), app não tenta
  retomar posição — reposiciona do zero na próxima playlist válida (RN-06).
- **Duplo envio do mesmo `execucaoId`** (retentativa depois de resposta
  perdida): servidor responde `duplicado` — tratado como sucesso, remove da
  fila (não é erro).

## 8. Direitos e obrigações que viram tela

Não se aplica — não há dado pessoal processado neste app (ver
`docs/inventario-de-dados.md`), não há conta de usuário, não há coleta de
dado de espectador. Nenhuma obrigação de titular de dado (LGPD) vira tela
aqui.

## 9. A métrica de sucesso e os eventos que a alimentam

**Métrica principal**: proporção de exibições concluídas (`STATE_ENDED`)
que resultam em `contabilizado` ou `duplicado` no servidor, sem
intervenção manual — instrumentada pelo próprio contrato de proof-of-play,
não por um evento de analytics separado.

Eventos (nomeados por convenção `categoria:objeto_acao`):

| Evento | Onde é emitido | Propriedades | Pergunta que responde |
|---|---|---|---|
| `exibicao:execucao_iniciada` | Aparelho, antes do `play()` | `execucaoId`, `janelaId`, `itemProgramacaoId`, `criativoId` | Quantas exibições começaram? |
| `exibicao:execucao_terminada` | Aparelho, no `STATE_ENDED` | `execucaoId`, `terminadoEm` | Quantas terminaram de verdade (vs. começaram)? |
| `proofofplay:lote_enviado` | Aparelho, ao chamar `POST /played` | tamanho do lote | Quantos eventos por envio (eficiência do lote)? |
| `proofofplay:evento_resolvido` | **Servidor**, na resposta de `/played` | `execucaoId`, `status` (contabilizado/duplicado/etc.) | Quantos viraram comprovante — a métrica principal |
| `proofofplay:evento_perdido` | Aparelho, ao descartar por estouro de fila ou expiração de 7 dias | motivo (`estouro`/`expirado`/`payload_invalido`) | Quanto está sendo perdido, e por quê? |

Os dois primeiros (`execucao_iniciada`, `execucao_terminada`) hoje só
existem como estado na tabela SQLite (`terminado_em IS NULL` vs. preenchido),
não como evento emitido explicitamente para um coletor externo — não há
serviço de telemetria neste projeto (`CONSTRAINTS.md`, fora de escopo). A
pergunta de negócio "quantos ontem?" (estação 6) é respondida consultando a
fila local (`FilaProofOfPlay.pendentes()`/`perdas()`) e, do lado do
servidor, pela resposta de `/played` — que é onde a métrica principal de
fato se consolida, porque é lá que "contabilizado" vira crédito de verdade.

## 10. O que fica fora desta versão

Ver `CONSTRAINTS.md` (relatório na TV, telemetria rica, OTA, login) e
`docs/proximas-versoes.md`.

## Contrato com o backend

O contrato novo (envelope com `versaoContrato`, `janelaId`,
`itemProgramacaoId`, `criativoId`, `POST /played` em lote com `execucaoId`)
está sendo implementado em paralelo no backend `sancompany/mostrai`, em
outra sessão. Este app opera nas duas formas desde o começo (ver
`network.PlaylistJson`): array puro do contrato antigo → modo degradado;
envelope com `versaoContrato` → contrato novo.

Duas garantias que este app depende do backend manter:

- `itemProgramacaoId` deriva da posição na sequência congelada da hora, não
  do índice do array da resposta (RN-09 depende disso).
- `criativoId → url` é imutável — criativo trocado é `criativoId` novo (usa-se
  como chave de cache de mídia sem revalidar, ver bloco de cache local).
