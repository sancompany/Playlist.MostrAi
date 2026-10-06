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
| Envio de proof-of-play | a cada 60 s e quando a rede volta (até 20 lotes seguidos por rodada) |
| `POP_RETENTION` | **até a confirmação do servidor** — nunca apagado só por idade (02/10/2026; o servidor aceita até 7 dias depois do fim da janela e responde status final depois disso) |
| Segmentos operacionais | o aberto a cada 15 min, os fechados assim que houver rede (`POST /player/:id/operacao`, até 200 por lote) |
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

**Atualização por pendrive (3.0.1 em diante, `docs/atualizacao-usb.md`):**
1. O técnico conecta um pendrive com `MOSTRAI/update/` (pacote oficial).
2. O Player percebe (aviso do Android, ou já conectado ao abrir, ou o vigia
   de 30 s), copia o APK para a área privada e confere pacote, chave
   definitiva, versão e SHA-256 — com o vídeo tocando.
3. Modal "ATUALIZAÇÃO MOSTRAÍ — Nova versão encontrada — Atual / Nova —
   [Atualizar agora] [Depois]".
4. Atualizar agora → instalador do Android → Instalar → o Player volta
   sozinho na versão nova, com o mesmo cadastro e as mesmas filas.
5. Depois → não reaparece enquanto o mesmo pendrive estiver conectado.

## 3. Estados visuais

| Estado | Quando | O que aparece | `estado` no heartbeat |
|---|---|---|---|
| Abertura | Primeiro `onStart` do processo | Vídeo de marca (`res/raw/video_abertura.mp4`), uma vez, sem proof-of-play | — |
| Não provisionado | Sem credencial, ou credencial recusada (401) | Tela de instalação: ID da tela, código, CONECTAR, teclado na tela | `NOT_PROVISIONED` (não é enviado — sem credencial não há heartbeat) |
| Carregando | Provisionado, antes da primeira playlist | Arte "Atualizando conteúdo…" | `IDLE` |
| Player | Item com `url` | Vídeo tela cheia | `PLAYING` |
| Cartão local | Item sem `url` (pelo tempo do item), tela em reparo/inativa (403), fora do horário | Degradê de marca, sem legenda | `IDLE` ou `OUT_OF_SCHEDULE` |
| Sem conteúdo / erro | Playlist vazia, sem servidor e sem cache, ou uma volta inteira sem nenhuma exibição | Arte "Não foi possível carregar a programação" | `NO_PLAYLIST`, `DOWNLOAD_ERROR` ou `PLAYBACK_ERROR` |
| Institucional de reserva | Programação comercial vencida (passou de `janelaFim`) sem playlist nova, ou relógio não confiável | Só os vídeos institucionais da Mostraí já guardados, em laço, sem comprovante; sem nenhum guardado, o cartão local | `IDLE` (a tela está no ar; 05/10/2026 — antes `NO_PLAYLIST`); `NO_PLAYLIST` só sem institucional guardado |
| Atualização por pendrive | Pendrive com atualização válida e mais nova; ou falta liberar "instalar apps"/acesso ao pendrive | Sobreposição "ATUALIZAÇÃO MOSTRAÍ" com dois botões (Atualizar agora / Depois; Permitir atualizações / Depois; Permitir acesso / Agora não). Some sozinha depois de 2 min sem resposta, como "Depois" | o do vídeo que continua por trás |
| Pedido de PIN | VOLTAR com o app operando e `pinSaida` recebido | Sobreposição "PIN PARA SAIR" com teclado numérico e, embaixo, o bloco técnico de suporte (só para quem está diante do PIN) | o do vídeo que continua por trás |

O público **nunca** vê "sem internet": a falta de rede só aparece no bloco
de suporte da tela de PIN (instalação, conexão, último contato com o
servidor, programação válida até, mídias em cache x/y, espaço livre, fila
de comprovantes, tempo operacional a enviar, versão, aparelho, Android,
estado, último erro, se há hora do servidor neste boot, se o retorno
automático está bloqueado, e "PRONTO PARA OFFLINE ATÉ …" ou "NÃO PRONTO
PARA OFFLINE: motivo"). Nunca chave, token, código de instalação,
cabeçalho, URL ou dado pessoal. `InfoSuporte`.

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
  (os 6 do contrato §8). ~~Ou por passar do horizonte de 7 dias + 1 h~~ —
  **SUPERADA (02/10/2026):** comprovante pendente nunca é apagado só por
  idade; uma TV móvel pode passar dias offline. Só saem sem ACK o órfão
  (evento sem exibição concluída) e o já em quarentena, passados 7 dias +
  1 h. Quarentena por bisseção (400/413) **só com prova** de que um irmão do
  mesmo lote foi aceito — sem essa prova, o servidor pode estar recusando
  tudo, e o lote fica com espera crescente (diário `FILA_RECUSADA`). Nunca
  sai por timeout, 5xx, 401, 403 ou reinício. Lotes de até 50, até 20 por
  rodada; espera 5 s → 15 s → 60 s → 5 min → 15 min → teto de 30 min,
  zerada quando a rede volta; 429 respeita `Retry-After`. Fila limitada a
  150.000 linhas (≈ 40 dias de uma tela cheia); se estourar, descarta o
  mais antigo e grava `FILA_CHEIA` no diário. `FilaProofOfPlay`.

- **RN-07 — Offline não autoriza inventar veiculação** (02/10/2026, Ponto
  Móvel — substitui "Offline não para a tela"). Sem rede ou com 5xx, a tela
  continua a última playlist válida (guardada em disco) e as mídias do
  cache **enquanto a janela dela vale** (`janelaFim`; sem ele, `janelaInicio`
  + 1 h). ~~Na virada da hora sem rede, segue a última que tinha.~~
  **SUPERADA:** passada a janela, o comercial para — nunca repete a última
  playlist para sempre, nunca gera comprovante fora da janela — e a tela
  exibe só o institucional da Mostraí já guardado (`contabiliza: false`,
  nunca reduz obrigação de anunciante); sem ele, o cartão local. O "agora"
  vem de relógio confiável (`RelogioConfiavel`): a âncora do servidor;
  depois de um reboot sem rede, o relógio da TV só vale se não estiver
  atrás do último instante que o servidor já mostrou — senão, nenhum
  comercial. Hash divergente e falta de rede nunca tocam a URL remota.
  Prefetch: com a programação vencida, só o institucional é baixado.
  `Playlist.comercialAutorizadoEm`, `InstitucionalLocal`.

- **RN-07a — Cache que sobrevive a dias offline.** Mídias em `filesDir/midia`
  (não em `cacheDir`, que o Android limpa sozinho com pouco espaço; o cache
  antigo migra na primeira execução). Download atômico (`.tmp` + rename) e
  SHA-256 conferido. Nunca apaga mídia referenciada pela playlist guardada
  nem pelo institucional (chaves protegidas); o resto sai por LRU quando o
  espaço livre cai abaixo da reserva (o maior de 512 MB e 10 % do disco).
  Sem rede, não insiste a cada item (60 s de silêncio, ou até a rede voltar).
  `CacheMidia`.

- **RN-07b — Tempo operacional é fato local, em segmentos.** (05/10/2026,
  contrato do backend #114; as "sessões" de 02/10 foram **SUPERADAS**.)
  Enquanto a tela exibe (`PLAYING` ou `IDLE`, ciclo ativo, Activity na
  frente) há um segmento aberto (SQLite, `RegistroOperacional`), medido pelo
  relógio **monotônico** do boot (`bootId` = `b<BOOT_COUNT>.<aleatório>`,
  `seq` crescente que nunca se repete); checkpoint a cada 30 s; parar de
  exibir fecha. Mais de 6 h − 1 min rola para o próximo `seq`. Vai ao
  servidor como `{bootId, seq, inicio, fim}` no relógio **do servidor** — o
  `servidorAgora` recebido no mesmo boot é a âncora; sem âncora, espera. Se
  o boot terminar sem nunca ter falado com o servidor, os segmentos dele são
  descartados no boot seguinte e contados no diário (`OPERACAO_SEM_ANCORA`)
  — nunca vão com o relógio da TV. Queda de energia fecha no último
  checkpoint (nunca inventa tempo). `ok` confirma; `item_invalido` e
  `ignorado` são finais; 404/5xx/rede guardam e tentam em 15 min. O Player
  registra fatos; o backend decide o que valem.

- **RN-08 — Config só é marcada como aplicada depois de aplicada.**
  `configVersion` do heartbeat diferente da aplicada → `GET /config`
  (serializado, uma busca por vez); versão e conteúdo são gravados num
  commit só; margens e horário valem na hora, sem reiniciar app nem vídeo.
  Falha → `CONFIG_FALHOU` no diário e nova tentativa no próximo heartbeat.
  `Sincronizacao`, `ConfigAparelho.aplicarConfig`.

- **RN-09 — Saída só com PIN, e só com PIN recebido.** `pinSaida` é global,
  4 a 8 dígitos, vem da config. Com `pinSaida: null` o VOLTAR não abre
  nada — não existe PIN padrão. 3 erros bloqueiam por 5 s, dobrando até
  5 min. PIN certo grava `saidaAutorizada` (commit síncrono), cancela os
  alarmes do watchdog e fecha o app. `onStart` e `BootReceiver` rearmam.
  Tecla segurada (repetição) não digita nem abre o PIN duas vezes.
  `TelaPinSaida`, `Watchdog`.

- **RN-10 — Watchdog.** (02/10/2026: retorno rápido.) Saiu da frente sem
  PIN (HOME, outro app) → alarme **exato** de retorno em 5 s, repetido em
  10 s, 20 s, 40 s e 60 s enquanto o Player não voltar; TV em standby
  (`PowerManager.isInteractive` falso) não é acordada. Por trás, o alarme
  de segurança a cada 60 s (crescendo até 16 min enquanto a reabertura não
  pega); 90 s sem sinal de vida (o Player marca a cada 30 s) → reabre — cobre
  crash. O HOME em si não é interceptável (nenhum app consegue, e declarar
  launcher o instalador da TCL recusa). Quatro estados: **não provisionado** → não reabre (o instalador pode estar
  configurando Wi-Fi ou a TV), mas o alarme segue agendado; **provisionado**
  → reabre; **saída autorizada por PIN** → não reabre nem reagenda; **abrir
  o app de novo** (ícone ou boot) → rearma. Substitui o launcher `HOME`, que
  o instalador da TCL recusa
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
| "ATUALIZAÇÃO MOSTRAÍ", "Nova versão encontrada.", "Atual: … / Nova: …", "Atualizar agora", "Depois" | Modal do pendrive | `strings.xml` |
| "Para atualizar o Mostraí pelo pendrive, permita instalações pelo Mostraí Player." / "Permitir atualizações" | Modal, sem "instalar apps" liberado | `strings.xml` |
| "Pendrive conectado. Para procurar atualização do Mostraí, permita o acesso ao pendrive." / "Permitir acesso" / "Agora não" | Modal, sem acesso ao pendrive | `strings.xml` |
| "Verificar USB", "Instalar atualização", "Testar ligar tela (2 min)" | Bloco técnico (tela do PIN) | `strings.xml` |

## 6. Quando dá errado

- **Rede cai no meio de uma exibição**: o vídeo segue; busca e envio ficam
  para depois. Quando a rede volta: envia a fila, manda heartbeat e busca a
  playlist se a atual não veio do servidor.
- **Servidor fora na virada da hora**: segue a última playlist válida até
  o fim da janela dela; depois, só o institucional guardado (RN-07).
- **Queda de internet mais longa**: comprovantes e segmentos operacionais
  ficam guardados até a confirmação; ao reconectar, saem em lotes (até 20
  por rodada), a espera zera e a playlist é buscada na hora. Operação
  comercial prolongada sem conexão **não** é cenário suportado (decisão de
  05/10/2026: a Mostraí é *online-first*; ponto sem internet local usa dados
  móveis) — passada a janela autorizada, a tela fica no institucional.
- **Sem servidor e sem cache**: "Não foi possível carregar a programação",
  nova tentativa a cada 60 s.
- **Mídia não toca**: pula o item; uma volta inteira sem nenhuma exibição
  mostra o cartão de erro e espera 10 s antes de tentar de novo — nunca
  laço apertado.
- **Admin revoga o Player**: próximo heartbeat (≤ 15 s) recebe 401 → tela
  de instalação. A fila fica; depois de reprovisionar como a mesma tela, ela
  é enviada.
- **Reboot**: `BootReceiver` rearma o watchdog e abre o app (um
  `QUICKBOOT_POWERON` com a TV ligada há mais de 10 min é ignorado); a
  playlist guardada, as mídias e a fila sobrevivem, a posição na hora vem do
  relógio confiável (nunca recomeça do zero), e o segmento operacional
  aberto fecha no último checkpoint. Em TV Android 10+, abrir sozinho
  depende de "Exibir sobre outros apps" (`docs/android-modernizacao.md`).

- **Pendrive com APK errado** (outro app, outra chave, versão igual ou
  anterior, `update.json` que não confere, arquivo corrompido): nenhum
  modal; o motivo fica no bloco técnico e, menos "versão igual" e pendrive
  sem pacote, no diário (`ATUALIZACAO_RECUSADA`).
- **Pendrive arrancado durante a cópia, erro de leitura, pouco espaço**:
  nada parcial fica no aparelho, nada é apagado para abrir espaço, a versão
  atual segue tocando.
- **Instalação cancelada ou recusada pelo Android**: o Player volta na
  versão atual; bloco técnico "Instalação não concluída".

## 7. Direitos e obrigações que viram tela

Não se aplica — nenhum dado pessoal (`docs/inventario-de-dados.md`).

## 8. Métrica de sucesso

Proporção de exibições concluídas (`STATE_ENDED`) que viram `contabilizado`
ou `duplicado` no servidor, sem intervenção manual. Medida pela resposta de
`/played` (servidor) e pela fila local (`fila.pendentes`/`maisAntigoEm`,
que vão em todo heartbeat). Não há serviço de telemetria (`CONSTRAINTS.md`).

## 9. O que fica fora

Ver `CONSTRAINTS.md`, seção "Fora do MVP".
