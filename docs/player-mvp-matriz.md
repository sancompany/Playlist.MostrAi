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
