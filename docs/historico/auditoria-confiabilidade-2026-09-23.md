> **HISTÓRICO — não é a regra atual.** Documento de antes da reestruturação
> para o MVP (26/09/2026, versão 2.0.0). V1, `/hello`, OTA, Device Owner,
> kiosk, painel técnico, provisionamento por JSON/pendrive/ADB, `baseUrl` e
> rotação variáveis **não existem mais** no Player. O protocolo em vigor é
> `sancompany/MostrAi` → `docs/player-mvp-contract.md`; o estado atual está
> em `README.md` e `docs/funcional.md`. Mantido só pelo histórico de bugs e
> decisões.

# AUDITORIA FINAL DO MOSTRAÍ PLAYER

Auditoria de confiabilidade iterativa e adversarial, 23/09/2026. Branch
`claude/festive-goldberg-4gdhqi`, PR #2. Nenhuma feature nova: só defeitos
provados, corrigidos com teste de regressão que falhava antes da correção.

> **O que este documento afirma, e o que não afirma.** Nenhum novo defeito
> reproduzível foi encontrado nas duas últimas rodadas completas de auditoria
> verificáveis neste ambiente (Robolectric, servidor HTTP local, SQLite real).
> Isto **não** é uma declaração de que o Player não tem defeitos, de que está
> pronto para produção, nem de que o hardware foi validado: nada aqui rodou numa
> TV SEMP TCL 32S6500S. A seção 8 lista o que só a TV confirma.

---

## 1. Baseline

| Item | Valor |
|---|---|
| HEAD | `4fd9a42` |
| Testes | 209, 0 falhas |
| `clean assembleDebug testDebugUnitTest` | verde |
| `lintDebug` | **falhava**: 1 erro (`ExpiredTargetSdkVersion`, regra da Play Store, inaplicável a sideload com `targetSdk 26` deliberado) + 30 avisos |
| Ação no baseline | só essa regra desligada, com justificativa em `app/build.gradle.kts`; os 30 avisos classificados, nenhum era defeito |
| Linhas Kotlin | produção 4.946 · testes 2.969 |

## 2. Ciclos e rodadas

| Rodada | Escopo | Bugs novos | Severidade máx. |
|---|---|---|---|
| A · Ciclo 0 | baseline | — | — |
| A · Ciclo 1 | lifecycle | BUG-001, 002, 003 | HIGH |
| A · Ciclo 2 | concorrência | BUG-006…012 | HIGH |
| A · Ciclo 3 | persistência/crash/energia | BUG-004, 013, 014, 015, 016; ROB-003, 004 | HIGH |
| A · Ciclo 4 | proof-of-play adversarial | BUG-005, 017, 018, 019; ROB-005 | HIGH |
| A · Ciclo 5 | cache/mídia | BUG-020 | MEDIUM |
| A · Ciclo 6 | contratos hostis | BUG-021, 022; ROB-006 | HIGH |
| A · Ciclo 7 | auth/provisionamento | BUG-023; ROB-002 | MEDIUM |
| A · Ciclo 8 | config desejada × aplicada | BUG-024 | LOW |
| A · Ciclo 9 | horário operacional | BUG-025, 026 | MEDIUM |
| A · Ciclo 10 | heartbeat/hello | ROB-007 (+ conserto do harness) | LOW |
| A · Ciclo 11 | OTA | — (DÍVIDA) | — |
| A · Ciclo 12 | kiosk/watchdog | BUG-027 | MEDIUM |
| A · Ciclo 13 | painel/PIN | BUG-028; ROB-008 | MEDIUM |
| A · Ciclo 14 | recursos | — (DÍVIDA) | — |
| A · Ciclo 15 | segurança local | ROB-009 | LOW |
| A · Ciclo 16 | manifesto/build/release | — | — |
| A · Ciclo 17 | matriz V1↔V2 | — | — |
| A · Ciclo 18 | mutação dos testes | 5 de 15 mutações sobreviviam → 5 testes | — |
| A · Ciclo 19 | 15 invariantes | 4 lacunas de teste fechadas | — |
| B | efeitos colaterais das correções | BUG-029 (regressão do BUG-020) | MEDIUM |
| C | áreas menos lidas | BUG-030, 031 | MEDIUM |
| D | efeitos mascarados | BUG-032 (mascarado pelo BUG-012) | MEDIUM |
| E | recuperação em campo | BUG-033 | MEDIUM |
| F | releitura completa | BUG-034, 035 (035: regressão do BUG-014) | LOW |
| G | releitura completa | BUG-036 | LOW |
| H | entrada de operador | BUG-037, 038 | LOW |
| **I** | **releitura completa + varredura mecânica** | **nenhum** | — |
| **J** | **mutação das correções recentes + resistência 2h** | **nenhum** | — |

## 3. Bugs corrigidos

Cada linha tem um teste que falhava antes da correção, salvo onde indicado.

| ID | Sev | Causa raiz | Impacto | Correção | Teste | Commit |
|---|---|---|---|---|---|---|
| BUG-001 | MEDIUM | corrotina iniciava exibição depois do `onStop` | linha órfã na fila | geração invalidada no `onStop`, guarda `iniciada` | `CicloDeVidaTest` | `434088e` |
| BUG-002 | HIGH | pedido de playlist forçado descartado com busca em voo | tela sem exibição até a virada de janela | pedido acumulado e reexecutado | `CicloDeVidaTest` | `434088e` |
| BUG-003 | MEDIUM | sinal de vida não renovado em regime | watchdog reabria player saudável a cada ~7 min | renovação a cada 60 s | `CicloDeVidaTest` | `434088e` |
| BUG-004 | HIGH | `SQLiteException` sem tratamento em `onCreate` e corrotinas | crash em laço com disco cheio | diário e fila nunca lançam; item toca sem comprovante | `PersistenciaTest` | `f63afeb` |
| BUG-005 | MEDIUM | falha de item chamava `avancar()` direto | laço apertado, diário tomado | pausa de 10 s após volta sem exibição | `ReproducaoTest` | `08727ec` |
| BUG-006 | HIGH | mídia com hash divergente rebaixada a cada escalonamento | banda da loja queimada | silêncio de 30 min | `CacheConcorrenciaTest` | `d2421dd` |
| BUG-007 | HIGH | fila segurava a trava do banco durante a rede | transição de item travava até 10 s | envio fora da trava | `FilaConcorrenciaTest` | `5fabfa0` |
| BUG-008 | HIGH | trava global no cache | item já em cache esperava download alheio (30 s) | trava por arquivo | `CacheConcorrenciaTest` | `d2421dd` |
| BUG-009 | HIGH | motivo de falha em campo compartilhado | mídia rejeitada por hash podia tocar | resultado por chamada | `CacheConcorrenciaTest` | `d2421dd` |
| BUG-010 | HIGH | duas sincronizações de config simultâneas | config regredia (13 → 12) | serializada | `ConfigConcorrenciaTest` | `6a46b59` |
| BUG-011 | HIGH | build com falha voltava a AVAILABLE todo heartbeat | APK rebaixado a cada 5 min | espera de 6 h | `AtualizadorConcorrenciaTest` | `b43dc3f` |
| BUG-012 | MEDIUM | download do OTA dentro do monitor | heartbeat travava 30 s | download fora da trava | `AtualizadorConcorrenciaTest` | `b43dc3f` |
| BUG-013 | MEDIUM | 200/401 de requisição com a chave antiga decidia a candidata | perda da única chave válida | só julga a chave enviada | `RotacaoConcorrenciaTest` | `c908e13` |
| BUG-014 | MEDIUM | `DOWNLOADING` persistido sem dono após reinício | OTA parado para aquele build | só vale no processo que baixa | `AtualizadorPersistenciaTest` | `38fa2d4` |
| BUG-015 | HIGH | manifesto repetido tratado como novidade | APK rebaixado + diálogo novo a cada 5 min | mesmo build com APK em disco não é novidade | `AtualizadorPersistenciaTest` | `38fa2d4` |
| BUG-016 | LOW | âncora de relógio de outro boot aceita | posição temporal errada | `BOOT_COUNT` junto da âncora | `PlaylistCacheTest` | `e15257a` |
| BUG-017 | LOW | órfão e quarentena contados como perda | painel mostrava perda inexistente | só comprovante conta | `FilaPerdasTest` | `afaab11` |
| BUG-018 | LOW | `Retry-After` sem teto | comprovante expirava sem reenvio | teto de 30 min | `FilaPerdasTest` | `50bf413` |
| BUG-019 | HIGH | ciclo parado à espera de um diálogo que podia não vir | tela congelada (V1: sem fim) | retoma em `onResume` + timer de 30 s | `InstalacaoTest` | `7ad83f3` |
| BUG-020 | MEDIUM | página HTML de portal cativo virava cache (V1) | criativo falhando para sempre | recusa HTML/JSON | `CacheMidiaHashTest` | `612cdc1` |
| BUG-021 | HIGH | `HH:MM:SS` e faixa ilegível descartados | tela apagada o dia inteiro, todo dia | aceita segundos; dia ilegível acende | `HorarioHostilTest` | `bff0058` |
| BUG-022 | MEDIUM | playlist vazia não desenhava nada | "carregando" para sempre | tela institucional | `ReproducaoTest` | `dff0e3e` |
| BUG-023 | MEDIUM | Activity exportada aceitava extras de qualquer app | outro app trocava o servidor e levava a chave | release só provisiona aparelho novo | `SegurancaLocalTest` | `b650815` |
| BUG-024 | LOW | `horasEntreTentativas` ignorado | admin mudava e nada acontecia | lido da config | `ReceptorInstalacaoTest` | `9120b94` |
| BUG-025 | LOW | fechamento por horário não invalidava a geração | anúncio tocava com a loja fechada | geração invalidada | `HorarioCicloTest` | `90da097` |
| BUG-026 | MEDIUM | faixa que cruza a meia-noite aplicada à madrugada errada | acesa/apagada na madrugada errada | madrugada pertence ao dia seguinte | `HorarioOperacionalTest` | `423d11d` |
| BUG-027 | MEDIUM | VOLTAR fazia `finish()` | anúncio cortado; launcher da TV por 5–7 min | VOLTAR consumido | `SegurancaLocalTest` | `52ce519` |
| BUG-028 | MEDIUM | painel nunca fechava | tela coberta por dias com anúncios contando | fecha após 3 min sem tecla | `PainelTest` | `e7593c8` |
| BUG-029 | MEDIUM | correção do BUG-020 recusava todo `text/*` | cache desligado se o storage servir `text/plain` | só HTML/JSON | `CacheMidiaHashTest` | `793f065` |
| BUG-030 | MEDIUM | rede que volta não rebuscava a playlist | até 15 min de anúncios da hora errada | rebusca se a origem é cache | `CicloDeVidaTest` | `3927dec` |
| BUG-031 | LOW | `NO_PLAYLIST` documentado e nunca reportado | badge do admin nunca acende | reportado | `ReproducaoTest` | `72608d5` |
| BUG-032 | MEDIUM | download do APK dentro do heartbeat | efeitos do heartbeat atrasados pelo download | thread própria | `AtualizadorConcorrenciaTest` | `663bdcd` |
| BUG-033 | MEDIUM | qualquer token gravado bloqueava o pendrive | TV irrecuperável sem limpar dados | só o mesmo token é ignorado | `ConfigExternaPendriveTest` | `26398da` |
| BUG-034 | LOW | heartbeat 2xx não-JSON = "sem atualização" | APK pronto apagado | resposta inválida | `AtualizadorPersistenciaTest` | `bfb4852` |
| BUG-035 | LOW | download superado zerava a marca do outro | download duplicado, FAILED 6 h | marca apagada só pelo dono | `AtualizadorConcorrenciaTest` | `9651905` |
| BUG-036 | LOW | sessões do PackageInstaller acumulavam | centenas de MB no sistema | abandona as anteriores | `InstalacaoTest` | `2757597` |
| BUG-037 | LOW | `baseUrl` com barra final → `//playlist` | 404, tela presa em cache | normalizado na leitura | `RotacaoConcorrenciaTest` | `f77d2d4` |
| BUG-038 | LOW | valores do pendrive sem `trim` | TV "provisionada" que nunca autentica | aparados | `ConfigExternaTest` | `1c1e3a2` |

Robustez (sem defeito funcional, mudança pequena): ROB-002 `bb5deba` (token tardio trocado na hora),
ROB-003 `da9b3c9` (credenciais do token numa escrita síncrona, **sem teste**: perda de energia não é
simulável), ROB-004 `9ba442f` (`fsync` antes de promover mídia, **sem teste**, mesmo motivo), ROB-005
`4dadda0` (backoff em 401), ROB-006 `2335222` (resultado malformado isolado), ROB-007 `478cdde` (hello
repetido), ROB-008 `e7593c8` (servidor sem credencial no painel), ROB-009 `385d563` (boot arma o watchdog).

Não aplicados de propósito: ROB-001 (escrita do diário na thread principal; mover mudaria a ordem
`limparErros`/`registrar`).

## 4. As 15 invariantes

| # | Invariante | Código que garante | Teste que verifica | Situação |
|---|---|---|---|---|
| 1 | Só mídia válida é reproduzida | `CacheMidia.baixarPara` (hash, Content-Type, vazio), `Resolucao.podeTocarDaUrlRemota`, `PlayerActivity.mostrarVideo` | `CacheMidiaHashTest` (hash errado impede URL remota; portal cativo), `CacheConcorrenciaTest` | Garantida com `contentHash`. Sem hash (V1) "válida" = não vazia e não textual — limite do contrato V1 |
| 2 | Hash incorreto nunca vira cache válido | `CacheMidia.baixarPara` (confere antes do `renameTo`) | `CacheMidiaHashTest#hash errado nao vira cache`, `#nenhum tmp sobra` | Garantida |
| 3 | execucaoId estável | `FilaProofOfPlay.registrarInicio` (UUID uma vez), `adiarComBackoff` só reagenda | `FilaPerdasTest#retentativa leva o mesmo execucaoId` | Garantida (teste novo) |
| 4 | Execução incompleta não vira confirmação | `concluirExibicao` só em `STATE_ENDED`; `elegiveisParaEnvio` exige `terminado_em` | `FilaProofOfPlayTest#linha sem terminadoEm nao e enviada`, `CicloDeVidaTest#parar com anuncio tocando…`, `#parar a activity com video resolvendo…` | Garantida |
| 5 | Proof válido não é descartado antes de lixo/órfão | `ProofOfPlayDb.proximoADescartar` | `ProofOfPlayDbTest` (órfão → quarentena → velho), `FilaPerdasTest#fila cheia…` | Garantida |
| 6 | Update de DB não destrói proof | `ProofOfPlayDb.onUpgrade` incremental, `onDowngrade` no-op | `FilaEscalaEQuarentenaTest#migracao de v1 para v2 preserva os eventos` | Garantida |
| 7 | Nunca perde a única chave funcional na rotação | `MostraiApi.promover/descartarChaveCandidata` (só a chave enviada decide) | `RotacaoConcorrenciaTest`, `MostraiApiAutenticacaoTest#candidata recusada nao destroi…`, `SincronizacaoV2Test#nova chave vira candidata…` | Garantida no player; depende da janela de 24h do backend |
| 8 | Config antiga não sobrescreve nova | `SincronizacaoV2.sincronizarConfigSeNecessario` (`@Synchronized`) | `ConfigConcorrenciaTest` | Garantida |
| 9 | Playlist antiga não sobrescreve nova | `PlayerActivity.atualizarPlaylist` (`buscandoPlaylist` + pedido acumulado) | `CicloDeVidaTest#duas buscas de playlist nunca ficam em voo…`, `#voltar…` (BUG-002) | Garantida (teste novo) |
| 10 | Fora do horário não contabiliza anúncio | `aplicarHorarioOperacional` (cancela, invalida geração), `tocarItemAtual`/`avancar` checam `foraDoHorario` | `HorarioCicloTest` | Garantida |
| 11 | Sem backend V2, V1 opera | `SincronizacaoV2` (404 = V1), `MostraiApi` legado | `SincronizacaoV2Test#404 no heartbeat…`, `#404 no hello…`, `#token sobrevive…`, `InstalacaoTest` (reproduz com `/player` 404) | Garantida |
| 12 | Erro importante sobrevive restart | `DiarioBordo` (SQLite) | `DiarioBordoTest#o erro sobrevive a uma instancia nova…`, `SincronizacaoV2Test#falha de config vira erro duravel…` | **Parcial**: sobrevive ao reinício, mas é limpo pelo fetch de playlist do boot; crash não é registrado (DOC-001, documentado) |
| 13 | Update não ocorre no meio de exibição | `tentarInstalarAtualizacao` só em `concluirExibicao` | `InstalacaoTest#atualizacao pronta nunca e pedida no meio da exibicao` | Garantida (teste novo) |
| 14 | Segredos não aparecem em UI/log | `DiarioBordo.sanitizar`, `PainelActivity.resumirChave/resumirServidor`; nenhum `Log` imprime chave/token | `DiarioBordoTest#mensagem nunca carrega segredo`, `PainelTest#painel nunca mostra a chave inteira…` | Garantida (teste novo para a UI) |
| 15 | Falha transitória não inutiliza o Player | fallback de cache de playlist, URL remota em falha de rede, `FilaProofOfPlay`/`DiarioBordo` sem exceção de SQLite, backoff | `PersistenciaTest`, `CacheMidiaHashTest#falha de rede continua permitindo…`, `FilaProofOfPlayTest#falha transitoria…`, `SincronizacaoV2Test#erro de rede nao suja…` | Garantida |

## 5. Estado final

| Item | Valor |
|---|---|
| HEAD de código | `1c1e3a2` (+ commit deste relatório) |
| Testes | **283**, 0 falhas (209 no baseline) |
| `clean assembleDebug assembleRelease` | verde (release sai **não assinado**, sem keystore — intencional) |
| `lintDebug` / `lintRelease` | 0 erros · 30 / 23 avisos, todos pré-existentes |
| CI | verde no HEAD `1c1e3a2` (run 35926590987) |
| Linhas Kotlin | produção 5.608 (+662) · testes 5.355 (+2.386) |
| Commits da auditoria | 47 desde `4fd9a42`, um por defeito (ou par inseparável) |

## 6. As duas rodadas limpas

- **Auditoria I:** releitura completa por subsistema (lifecycle, concorrência,
  persistência, fila, cache, parsers, auth, config, horário, heartbeat, OTA,
  kiosk, painel, recursos, segurança, V1↔V2) + varredura mecânica de padrões
  perigosos (`!!`, `first()`, índices, casts, `split`/`substring`, `lateinit`,
  bloqueios). Todos protegidos. Build, lint e 283 testes verdes.
- **Auditoria J:** 10 mutações nas correções B–H — todas mortas, cada uma pelo
  teste específico; teste de resistência de 2 h simuladas com rede alternando a
  cada 10 min: 0 exceções não capturadas, fila sem acúmulo de órfãos, ciclo
  avançando em todos os 120 minutos, requisições no volume esperado (11
  playlists, 26 heartbeats/hello, mídia baixada 2 vezes). Build, lint e testes
  verdes.

## 7. Riscos que este ambiente não resolve

| ID | Risco | Por quê não foi alterado |
|---|---|---|
| RSK-001 | 1ª exibição de criativo novo espera o download inteiro (minutos em internet lenta) | decisão de produto (streaming custa banda em dobro; pular muda a entrega) |
| RSK-005 | vídeo de abertura sem prazo: decodificador travado congela o app sem heartbeat | só reproduzível com o decodificador real |
| RSK-006 | fila/backoff usam relógio de parede: TV que perde a hora sem energia pode expirar ou adiar comprovantes | depende do relógio do aparelho |
| RSK-007 | backend que responda `400` para tudo põe a fila inteira em quarentena permanente | comportamento R4; aviso no checklist do backend |
| RSK-008 | criativo de fração de segundo vira comprovante em laço rápido | regra de duração mínima é do dono |
| OBS-001 | anúncio atrás do painel (90% opaco) conta como exibido | decisão de produto; painel agora fecha em 3 min |
| OBS-002/004 | diálogo de instalação sem ninguém: segura a exibição até o watchdog (~5 min); reoferta em 6 h | troca deliberada do OTA fase 1 |
| OBS-005 | bloqueio do PIN usa relógio de parede | contornável mudando a hora; documentado |
| DOC-001 | "erro durável" é limpo no fetch do boot; crash não é registrado | documentado no contrato §4.1 |
| — | Device Owner não habilitável (sem `DeviceAdminReceiver`) | depende de teste no aparelho (relatos de falha em TCL) |
| — | keystore de release definitivo | só o dono; **nenhum APK de produção deve ser distribuído antes** |

## 8. Checklist físico na TV (VALIDAÇÃO FÍSICA PENDENTE)

1. Instalar o APK de depuração por pendrive; o vídeo de abertura termina e o ciclo começa. Repetir 20 ligações/desligamentos (RSK-005).
2. Rotação (`rotacaoTela: 90`) e margens aplicadas no **primeiro** boot, sem precisar reabrir (RSK-009).
3. Vídeo toca sem artefato em `TextureView`; 1 h contínua sem travar.
4. Tirar da tomada por uma noite, ligar **sem rede**: anotar a data/hora do sistema antes do NTP (RSK-006); conferir que a fila não perde comprovantes ao reconectar.
5. `Settings.Global.BOOT_COUNT` incrementa a cada boot (BUG-016).
6. Painel: gesto OK×3, PIN, fecha com VOLTAR e sozinho após 3 min; o vídeo continua atrás sem interrupção.
7. VOLTAR na tela do player não faz nada; HOME e a tecla de configurações saem do app.
8. Watchdog: forçar parada pelo menu do sistema e confirmar a reabertura em ≤ 7 min; reboot e reabertura pelo `BootReceiver`.
9. OTA: com permissão de "fontes desconhecidas", o diálogo aparece **entre** itens; cancelar retoma a exibição; confirmar instala e o próximo heartbeat limpa o estado. Medir o tempo até o diálogo aparecer (deve ser < 30 s).
10. Pendrive com token novo recupera uma TV cujo token anterior falhou (BUG-033).
11. Horário `CUSTOM` com faixa que cruza a meia-noite, observado na virada.
12. Queda de rede atravessando a virada de hora: ao reconectar, a playlist nova entra em segundos (BUG-030).
13. `dpm set-device-owner` — só se o dono decidir habilitar o Device Owner.

## 9. Fidelidade de `docs/player-v2-contract.md`

Relido contra o código depois de cada correção. **Atualizado** (commits
`a16ee27`, `793f065`, `72608d5`, `bfb4852`, `86c7830` e o deste relatório):
rotação (§1.1), troca do token e bancada (§2.3/2.4), hello (§3), limites do erro
durável (§4.1), estados `NO_PLAYLIST`/`CONFIG_ERROR`/`DOWNLOAD_ERROR` (§4.2),
corpo 2xx não-JSON (§4.4), campos informativos e sincronização (§5), silêncio
de hash, `Content-Type`, `https`, playlist vazia (§6), `HH:MM:SS`, meia-noite,
faixa vazia, resolução de 1 min (§7), comportamento do OTA entre heartbeats
(§8.3), garantias do envio (§9.2.1), Device Owner (§12). Com essas mudanças, o
contrato descreve o que o código faz.

## 10. Fidelidade de `docs/player-v2-mostrai-checklist.md`

Atualizado: formato das faixas e dia inteiro (`00:00–24:00`), `min_build`
informativo, mídia em `https` com `Content-Type` de vídeo (`text/plain` aceito),
pedido de idempotência na troca do token, regra da rotação, comportamento do
OTA para a UI, histórico de `last_error_*` e o aviso de que **quarentena por
`400` é permanente** — erro do servidor responde `5xx`. Nenhum item do checklist
exige mudança no player.
