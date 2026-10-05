# Player MVP — matriz contrato × Player

Fonte da verdade do protocolo: `sancompany/MostrAi` → `docs/player-mvp-contract.md`
(lido em 26/09/2026, commit `72931ae` do backend). Se este repositório
divergir dele, o contrato vence.

## Matriz inicial (Player em `cf3980e`, antes da reestruturação)

| Endpoint | Backend espera | Player atual envia | Player atual recebe | Ação necessária |
|---|---|---|---|---|
| `POST /player/provisionar` | `{codigoTela, codigoInstalacao}`; 200 `{dispositivoId, chaveAparelho}`; 400/401/429/5xx | `{tokenProvisionamento}` (token do pendrive) | `dispositivoId`, `chaveAparelho` | Trocar o corpo para ID da tela + código de instalação digitados na TV; tratar 400/401 (sem retentativa)/429 (`Retry-After`)/5xx (repetição curta) |
| `GET /playlist/:id` | header `X-Aparelho-Key`; envelope `versaoContrato: 2`; 401 = reinstalar; 403 = tela em reparo/inativa | `X-Aparelho-Id` + `X-Aparelho-Key`, `X-Player-Contract` | envelope **ou** array V1 (modo degradado) | Só envelope; 401 apaga a credencial; 403 para de exibir anúncios e mantém a fila |
| `POST /player/:id/played` | `{eventos: [...]}` até 500 (corpo ≤ 100 KB); 6 status finais; 400/413 bisseção; 401/403 mantém | lote V2 **e** formato legado `{anuncianteId}` | `resultados` **ou** `{contou}` legado | Só lote; 413 igual a 400; 401 também dispara reinstalação |
| `POST /player/:id/heartbeat` | a cada **15 s**; `{estado, configVersionAplicada, criativoId, erro, fila}`; resposta `{configVersion, playlist.atualizar}` | a cada 5 min; + `versaoContrato`, `ultimaPlaylistOkEm`, `desvioRelogioMs`, `update` | + `margens`, `update` (OTA), `novaChave`, `servidorAgora` | 15 s; só os 5 campos; resposta só `configVersion` e `playlist.atualizar` |
| `GET /player/:id/config` | `{configVersion, margens, operacao{timezone, porDiaDaSemana, feriados}, pinSaida}` | — | `configVersion`, `margens`, `rotacaoTela`, `operacao.regime`, `pinPainel`, `update`, `cache`, `versaoMinimaBuild` | Só os 4 campos; horário sem `regime`; `pinSaida` 4–8 dígitos ou `null` |
| `POST /player/:id/hello` | **não existe** | ficha técnica do aparelho | `configVersion` | Remover |
| download de APK (OTA) | **não existe** | `GET` na `url` do manifesto | APK | Remover |

## Matriz final (Player 2.0.0, contrato relido em 26/09/2026, commit `72931ae`)

Todas as rotas autenticadas levam `X-Aparelho-Key` e `X-Player-Version:
<versionName>+<versionCode>` (`2.0.0+3`); `/provisionar` leva só
`X-Player-Version`. Base fixa: `https://mostrai.sancocore.com.br`. Cada linha
é coberta por `MostraiApiContratoTest` (caminho, método, headers, corpo e
tratamento de cada status) e pelos testes de ciclo em `ciclo/`.

| Endpoint | Request do Player | Backend espera | Response do backend | Player parseia | Match |
|---|---|---|---|---|---|
| `POST /player/provisionar` | `{codigoTela: "M-0235", codigoInstalacao: "7K4M-9Q2W"}` já normalizados (ID `M-` + ≥ 4 dígitos; código 8 caracteres do alfabeto, maiúsculo, `XXXX-XXXX`) | `{codigoTela, codigoInstalacao}`, código case-insensitive, hífen/espaço ignorados | 200 `{dispositivoId, chaveAparelho}`; 400; 401; 429 + `Retry-After`; 5xx | 200 → grava as duas (commit síncrono) e some com a tela; 400 → "Confira o ID e o código"; 401 → mensagem, **sem** retentativa; 429 → espera `Retry-After`; 5xx/rede → repete o **mesmo par** (2–60 s, dentro dos 5 min da repetição curta) | **MATCH** |
| `GET /playlist/:dispositivoId` | `GET`, sem corpo | chave válida | 200 envelope `{versaoContrato, janelaId, janelaInicio, janelaFim, servidorAgora, itens[]}`; 401; 403 | `janelaId`, `janelaInicio`, `servidorAgora`, `itens[].{itemProgramacaoId, criativoId, url, duracaoSegundos, contabiliza, contentHash}` (`institucional` só por `url`; `janelaFim`, `anuncianteId`, `autoanuncio` informativos, ignorados); sem `itens` ou `janelaId` → resposta inválida, mantém a última; 401 → reinstalação; 403 → cartão, apaga a playlist guardada, mantém a fila; 5xx/rede → última válida + cache | **MATCH** |
| `POST /player/:dispositivoId/played` | `{eventos: [{execucaoId, janelaId, itemProgramacaoId, criativoId, iniciadoEm, terminadoEm}]}`, até 50 por lote, `execucaoId` UUID estável | até 500 eventos, ≤ 100 KB, ids exatamente os da playlist | 200 `{resultados: [{execucaoId, status}]}` com 6 status finais; 400; 401/403; 413; 5xx | 6 status finais removem da fila; evento sem resultado fica; 400/413 → bisseção e quarentena do evento isolado; 401 → mantém a fila e reinstalação; 403 → mantém; 429 → `Retry-After`; 5xx/rede → espera crescente (5 s…30 min); expira só depois de 7 dias + 1 h | **MATCH** |
| `POST /player/:dispositivoId/heartbeat` | a cada 15 s: `{estado, configVersionAplicada, criativoId?, erro: {codigo, mensagem, ocorreuEm} \| null, fila: {pendentes, maisAntigoEm}}` | os 5 campos; `estado` nos 9 valores; versão só pelo header | 200 `{configVersion, playlist: {atualizar}}`; 401 (nunca 403) | `configVersion` ≠ aplicada → `GET /config`; `playlist.atualizar: true` → busca a playlist na hora; 401 → reinstalação | **MATCH** |
| `GET /player/:dispositivoId/config` | `GET`, sem corpo, serializado (uma busca por vez) | chave válida | 200 `{configVersion, margens{superior, direita, inferior, esquerda}, operacao{timezone, porDiaDaSemana, feriados}, pinSaida}`; 401 (nunca 403) | exige `configVersion`; margens 0–10 vmin aplicadas no `rotor` sem reiniciar; `operacao` sem regime (ponto sem horário = 24 h); `pinSaida` `^\d{4,8}$` ou `null` (sem saída); versão e conteúdo gravados juntos **depois** de aplicar | **MATCH** |

**CONTRACT_BLOCKERS**: nenhum. O `/hello` e o download de APK (OTA) da
matriz inicial não existem mais no Player (`GuardaMvpTest` falha se voltarem).

## Atualização 02/10/2026 — Ponto Móvel e "conectividade não é operação"

Contrato relido no backend, branch `claude/conectividade-nao-e-operacao`
([sancompany/MostrAi#113](https://github.com/sancompany/MostrAi/pull/113)),
`docs/player-mvp-contract.md` §7, §8.1 e §9.

| Endpoint | O que mudou no Player | Backend | Match |
|---|---|---|---|
| `GET /playlist/:dispositivoId` | `janelaFim` deixou de ser informativo: o comercial só toca dentro da janela (sem ele, `janelaInicio` + 1 h), medido por relógio confiável; `institucional` passou a ser lido para guardar o institucional de reserva | §7 marca "na virada da hora sem rede, continua a última" como SUPERADA | **MATCH** |
| `POST /player/:dispositivoId/played` | pendente fica até o ACK (não expira mais em 7 dias + 1 h); quarentena por bisseção só com prova de irmão aceito; até 20 lotes por rodada | inalterado — aceita até 7 dias depois da janela e responde status final depois | **MATCH** |
| `POST /player/:dispositivoId/operacao` | ~~**nova**~~ (superado em 05/10 — ver abaixo): `{sessoes: [{sessaoId, bootCount, inicioUptimeMs, fimUptimeMs, duracaoMs, inicioEm, fimEm, inicioServidorEm, fimServidorEm, encerrada, motivo}]}`, até 50; tira da fila local só `registrada`/`invalida`; 404 (backend antigo) → guarda e segue | §8.1: até 100 por lote; `resultados[{sessaoId, status: registrada\|invalida}]`; 400 lote ruim; 401/403 como §4; sem exigir tela Ativa | **MATCH** (coberto por `PontoMovelCicloTest` aqui e `tests/conectividade-operacao.test.js` lá) |

Heartbeat e config não mudaram. `GuardaMvpTest` passou de 5 para 6 rotas.

## Atualização 05/10/2026 — V1 de produção (3.0.0) contra o backend `main` pós-#114

Contrato relido no `main` do backend (commit `d267b8c`, Ponto Móvel V1
mesclado em [sancompany/MostrAi#114](https://github.com/sancompany/MostrAi/pull/114);
#113 fechado sem mesclar). Header `X-Player-Version: 3.0.0+4`.

| Endpoint | O que mudou no Player | Backend (`main`) | Match |
|---|---|---|---|
| `POST /player/:dispositivoId/operacao` | **reescrita para segmentos**: `{segmentos: [{bootId, seq, inicio, fim}]}`, até 200 por lote; `bootId` = `b<BOOT_COUNT>.<aleatório>` (`^[A-Za-z0-9._:-]{1,64}$`); `inicio`/`fim` ISO no relógio do **servidor** (âncora `servidorAgora` + monotônico do mesmo boot); segmento ≤ 6 h − 1 min (rola para o próximo `seq`); o aberto é reenviado crescendo; boot sem âncora não vai (descartado no próximo boot e contado no diário); `ok` confirma até o fim enviado, `item_invalido`/`ignorado` são finais; 404/5xx/rede → guarda e tenta de novo em 15 min | `src/player/operacao.js`: mesmos campos e regex; ≤ 6 h; até 2 min no futuro e 8 dias no passado; ≤ 200 por lote; idempotente por (tela, boot, seq), o reenvio só estende | **MATCH** (`PontoMovelCicloTest`, `OfflinePecasTest`) |
| `POST /player/:dispositivoId/heartbeat` | institucional de reserva (programação vencida offline) agora manda `estado: IDLE` (a tela está no ar), não `NO_PLAYLIST`; `NO_PLAYLIST` só sem nada para exibir | `PLAYING`/`IDLE` estendem o tempo operacional pelo heartbeat; `NO_PLAYLIST` é erro | **MATCH** |
| heartbeat (intervalo) | 15 s (`Produto.INTERVALO_HEARTBEAT_MS`, desde a 2.0.0) | `main` ainda diz 5 min e tolera 390 s; alinhado a 15 s / 2 min em [sancompany/MostrAi#115](https://github.com/sancompany/MostrAi/pull/115) | **MATCH depois do #115**; antes dele, só o admin fica mais lento para dizer "sem sinal" |
| `POST /player/:dispositivoId/played` | `iniciadoEm`/`terminadoEm` no relógio confiável (servidor), não no da TV; espera de reenvio impossível (> 31 min à frente, relógio que voltou) não prende mais o comprovante | inalterado | **MATCH** |

`playlist`, `config` e `provisionar`: sem mudança.

## Métricas antes → depois

Antes: `cf3980e` (baseline reconciliada, com o fix do `HOME`). Depois: 2.0.0.

| Métrica | Antes | Depois |
|---|---|---|
| Arquivos Kotlin de produção | 38 | 37 |
| Linhas Kotlin de produção | 5.608 | 4.127 (−26%) |
| Arquivos / linhas de teste | 44 / 5.355 | 46 / 5.048 |
| Testes | 283 | 276 |
| Activities | 2 (`PlayerActivity`, `PainelActivity`) | 1 |
| Services / Workers | 0 / 0 | 0 / 0 |
| Receivers | 3 | 2 (`BootReceiver`, `Watchdog$Receptor`) |
| Permissões | 5 | 3 (`INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`) |
| Dependências (`implementation` / teste) | 6 / 4 | 6 / 4 |
| Arquivos de `SharedPreferences` | 5 | 4 |
| Chaves de `SharedPreferences` | 29 | 14 |
| Bancos SQLite | 2 | 2 (fila de proof-of-play, diário) |
| Rotas HTTP | 6 + download OTA | 5 |
| Caminhos de provisionamento | 4 (build embutido, pendrive/JSON, token, ADB) | 1 (ID + código na TV) |
| Funções principais | playlist, cache, POP, heartbeat, config, hello, V1 degradado, OTA, kiosk/Device Owner, painel técnico, PIN do painel, rotação e `baseUrl` configuráveis, rotação de credencial | playlist, cache, POP, heartbeat, config, provisionamento, PIN de saída, watchdog |

O número de arquivos quase não cai porque o provisionamento na TV, o PIN de
saída são arquivos novos; a redução real está nas
linhas, nas chaves de estado e em tudo que deixou de existir.
