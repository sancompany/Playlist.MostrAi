# Definição funcional — Mostraí Player (MVP 2.0.0)

O que o sistema faz. App Android TV nativo com **uma Activity só**
(`PlayerActivity`): "tela" aqui é estado visual dela, nunca outra Activity.

O protocolo com o backend não é definido aqui. A fonte da verdade é
`sancompany/MostrAi` → `docs/player-mvp-contract.md`; a conferência campo a
campo está em `docs/player-mvp-matriz.md`. Se este documento divergir do
contrato, o contrato vence.

## 0. Valores fixos do produto

Constantes no APK (`Produto.kt`), nunca configuráveis por preferência, JSON,
pendrive, ADB ou backend:

| Item | Valor |
|---|---|
| `BASE_URL` | `https://mostrai.sancocore.com.br` |
| `ROTATION` | 90° (conteúdo girado no sentido horário; se a TV mostrar de ponta-cabeça, **outra build** com 270° — nunca rotação dinâmica) |
| `HEARTBEAT` | 15 s |
| Busca da playlist | virada de cada hora, a cada 15 min, quando o heartbeat pedir, quando a rede voltar |
| Envio de proof-of-play | a cada 60 s e quando a rede volta |
| `POP_RETENTION` | 7 dias (o servidor aceita até 7 dias depois do fim da janela) |
| `PROVISIONING` | ID da tela `M-xxxx` + código de instalação `XXXX-XXXX` |

## 1. Público-alvo

- **Espectador do comércio** — só assiste. Vídeo em laço, tela cheia, mudo,
  sem barra de sistema.
- **Instalador** (técnico do Mostraí ou do ponto) — instala o APK, digita
  o ID da tela e o código de instalação que o admin mostra, e vai embora.
- **Quem precisa sair do app na TV** (manutenção) — aperta VOLTAR e digita o
  PIN de saída definido no admin.
- **Backend `sancompany/MostrAi`** — decide playlist, janela, config, PIN e
  o que conta como comprovante. O app executa e relata.

## 2. Jornadas

**Instalação (uma vez por TV):**
1. Instala o APK (pendrive, instalador da TV).
2. Abre o app: toca o vídeo de abertura e cai na tela de instalação.
3. Digita o **ID da tela** (`235`, `0235`, `M0235`, `m-0235`… viram
   `M-0235`) e o **código de instalação** (8 caracteres, com ou sem hífen,
   maiúscula ou minúscula) que o admin mostra em Rede → Ponto → Tela.
4. CONECTAR → o app troca o código pela credencial, guarda, some com a tela
   de instalação e começa a operar.

**Operação (sozinha, todo dia):**
1. TV liga → `BootReceiver` abre o app.
2. Heartbeat a cada 15 s; config (margens, horário, PIN) quando a versão
   muda; playlist da hora.
3. Toca os itens em laço; cada exibição concluída vira proof-of-play na fila
   durável e é enviada em lote.

**Saída autorizada:**
1. VOLTAR no controle → aparece "PIN PARA SAIR" (o vídeo segue por trás).
2. PIN certo → o app fecha e o watchdog **não** o reabre.
3. Abrir o app de novo (ícone ou reboot) → operação normal e watchdog
   rearmado.

## 3. Estados visuais

| Estado | Quando | O que aparece | `estado` no heartbeat |
|---|---|---|---|
| Abertura | Primeiro `onStart` do processo | Vídeo de marca (`res/raw/video_abertura.mp4`), uma vez, sem proof-of-play | — |
| Não provisionado | Sem credencial, ou credencial recusada (401) | Tela de instalação: ID da tela, código, CONECTAR, teclado na tela | `NOT_PROVISIONED` (não é enviado — sem credencial não há heartbeat) |
| Carregando | Provisionado, antes da primeira playlist | Arte "Atualizando conteúdo…" | `IDLE` |
| Player | Item com `url` | Vídeo tela cheia | `PLAYING` |
| Cartão local | Item sem `url` (pelo tempo do item), tela em reparo/inativa (403), fora do horário | Degradê de marca, sem legenda | `IDLE` ou `OUT_OF_SCHEDULE` |
| Sem conteúdo / erro | Playlist vazia, sem servidor e sem cache, ou uma volta inteira sem nenhuma exibição | Arte "Não foi possível carregar a programação" | `NO_PLAYLIST`, `DOWNLOAD_ERROR` ou `PLAYBACK_ERROR` |
| Pedido de PIN | VOLTAR com o app operando e `pinSaida` recebido | Sobreposição "PIN PARA SAIR" com teclado numérico | o do vídeo que continua por trás |

Tudo é desenhado dentro do contêiner girado (`rotor`), então a tela de
instalação e o PIN também aparecem na orientação certa. As setas do controle
**não** se remapeiam: como o conteúdo gira junto com a TV montada de lado, o
layout já está de pé para quem olha, e a busca de foco do Android anda nas
coordenadas do layout — "cima" no controle já é "cima" para o instalador.

## 4. Regras de negócio

- **RN-01 — Só `STATE_ENDED` conta.** Exibição interrompida, falha de
  reprodução ou item trocado no meio não geram evento.
  `PlayerActivity.concluirExibicao`, `FilaProofOfPlay.registrarFalha`.

- **RN-02 — `execucaoId` nasce antes do `play()`**, persistido em SQLite, e
  é o mesmo em todas as retentativas (idempotência). `PlayerActivity.mostrarVideo`.

- **RN-03 — `contabiliza: false` nunca gera evento** (institucional,
  autoanúncio, mídia própria). `FilaProofOfPlay.registrarInicio`.

- **RN-04 — Posição na hora vem do servidor.** Início frio e janela nova
  calculam o índice por `janelaInicio`/`servidorAgora` + relógio monotônico
  (`PosicaoNaPlaylist`); item pego no meio é pulado, nunca há seek. Na mesma
  janela, reancora pelo `itemProgramacaoId` sem cortar o item no ar
  (`ReposicionamentoPlaylist`).

- **RN-05 — Só a presença de `url` decide vídeo × cartão**, nunca a flag
  `institucional`. `PlayerActivity.tocarItemAtual`.

- **RN-06 — Proof-of-play sai da fila só por status final do servidor**
  (os 6 do contrato §8), por quarentena depois de bisseção (400/413) ou por
  passar do horizonte de 7 dias + 1 h. Nunca por timeout, 5xx, 401, 403 ou
  reinício. Lotes de até 50; espera 5 s → 15 s → 60 s → 5 min → 15 min →
  teto de 30 min; 429 respeita `Retry-After`. Fila limitada a 50.000
  linhas. `FilaProofOfPlay`.

- **RN-07 — Offline não para a tela.** Sem rede ou com 5xx, continua a
  última playlist válida (guardada em disco) e as mídias do cache
  (endereçadas por `contentHash`, SHA-256 conferido). Na virada da hora sem
  rede, segue a última que tinha. Hash divergente nunca toca a URL remota.

- **RN-08 — Config só é marcada como aplicada depois de aplicada.**
  `configVersion` do heartbeat diferente da aplicada → `GET /config`
  (serializado, uma busca por vez); versão e conteúdo são gravados num
  commit só; margens e horário valem na hora, sem reiniciar app nem vídeo.
  Falha → `CONFIG_FALHOU` no diário e nova tentativa no próximo heartbeat.
  `Sincronizacao`, `ConfigAparelho.aplicarConfig`.

- **RN-09 — Saída só com PIN, e só com PIN recebido.** `pinSaida` é global,
  4 a 8 dígitos, vem da config. Com `pinSaida: null` o VOLTAR não abre
  nada — não existe PIN padrão. 3 erros bloqueiam por 5 s, dobrando até
  5 min. PIN certo grava `saidaAutorizada` (commit síncrono), cancela o
  alarme do watchdog e fecha o app. `onStart` e `BootReceiver` rearmam.
  `TelaPinSaida`, `Watchdog`.

- **RN-10 — Watchdog.** Alarme a cada 2 min (crescendo até 32 min enquanto a reabertura
  não pega); 5 min sem sinal de vida e sem saída autorizada → reabre o
  app. Substitui o launcher `HOME`, que o instalador da TCL recusa
  (`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`).

- **RN-11 — Margens são visuais.** 4 lados em vmin (0 a 10), aplicados como
  padding do `rotor`, que já é o quadro depois da rotação: "superior" é o
  topo que o espectador vê. `RotacaoTela.aplicar`.

- **RN-12 — Horário é o do ponto.** `operacao` da config: fuso, 7 dias,
  feriados que substituem o dia, faixa que cruza a meia-noite pertence ao
  dia em que começou. Ponto sem horário (ou config nunca recebida) = aberto
  24 h. Fora do horário: cartão local, nenhum anúncio, `OUT_OF_SCHEDULE`.
  Decidido offline com a última `operacao` guardada. `HorarioOperacional`.

- **RN-13 — Credencial.** `dispositivoId` + `chaveAparelho` guardados no
  armazenamento privado. A chave nunca aparece em tela, log, diário,
  exceção ou toast (`DiarioBordo` mascara). O código de instalação nunca é
  gravado e é apagado do campo depois do sucesso. Resposta 200 perdida →
  reenviar o mesmo par em até 5 min devolve a mesma credencial (o app tenta
  de novo sozinho só em falha transitória: 2 s, 5 s, 10 s, 20 s, 40 s, 60 s).
  Reinstalar como outra tela apaga a config e a playlist guardada da
  anterior.

- **RN-14 — 401 em qualquer rota autenticada** apaga a credencial (só se
  ainda for a mesma que foi recusada) e volta para a tela de instalação,
  **mantendo** a fila de proof-of-play. **403** (`/playlist`, `/played`):
  para de exibir anúncios (cartão local), apaga a playlist guardada e mantém
  a fila.

## 5. Textos que o sistema diz

| Texto | Onde | Arquivo |
|---|---|---|
| "MOSTRAÍ PLAYER", "ID DA TELA", "CÓDIGO DE INSTALAÇÃO", "CONECTAR" | Tela de instalação | `strings.xml` |
| "Conectando…" | Instalação, durante a troca | `strings.xml` |
| "Confira o ID da tela (ex.: M-0235)." / "Confira o código de instalação (8 letras e números)." | Formato inválido, antes de ir à rede | `strings.xml` |
| "Confira o ID e o código." | 400 | `strings.xml` |
| "ID da tela ou código de instalação inválido, expirado ou já usado." | 401 | `strings.xml` |
| "Muitas tentativas. Aguarde N s." | 429 na instalação; bloqueio do PIN | `strings.xml` |
| "Sem conexão com o servidor. Confira a internet e tente de novo." | Rede/5xx depois das retentativas | `strings.xml` |
| "Não foi possível salvar no aparelho. Tente de novo." | Falha ao gravar a credencial | `strings.xml` |
| "PIN PARA SAIR" / "PIN incorreto" | Pedido de PIN | `strings.xml` |
| "Atualizando conteúdo…" | Carregando (texto na arte) | `drawable-nodpi/institucional_carregando.png` |
| "Não foi possível carregar a programação" | Sem conteúdo (texto na arte) | `drawable-nodpi/institucional_erro.png` |

## 6. Quando dá errado

- **Rede cai no meio de uma exibição**: o vídeo segue; busca e envio ficam
  para depois. Quando a rede volta: envia a fila, manda heartbeat e busca a
  playlist se a atual não veio do servidor.
- **Servidor fora na virada da hora**: segue a última playlist válida.
- **Sem servidor e sem cache**: "Não foi possível carregar a programação",
  nova tentativa a cada 60 s.
- **Mídia não toca**: pula o item; uma volta inteira sem nenhuma exibição
  mostra o cartão de erro e espera 10 s antes de tentar de novo — nunca
  laço apertado.
- **Admin revoga o Player**: próximo heartbeat (≤ 15 s) recebe 401 → tela
  de instalação. A fila fica; depois de reprovisionar como a mesma tela, ela
  é enviada.
- **Reboot**: `BootReceiver` rearma o watchdog e abre o app; a posição na
  hora é recalculada pela próxima playlist.

## 7. Direitos e obrigações que viram tela

Não se aplica — nenhum dado pessoal (`docs/inventario-de-dados.md`).

## 8. Métrica de sucesso

Proporção de exibições concluídas (`STATE_ENDED`) que viram `contabilizado`
ou `duplicado` no servidor, sem intervenção manual. Medida pela resposta de
`/played` (servidor) e pela fila local (`fila.pendentes`/`maisAntigoEm`,
que vão em todo heartbeat). Não há serviço de telemetria (`CONSTRAINTS.md`).

## 9. O que fica fora

Ver `CONSTRAINTS.md`, seção "Fora do MVP".
