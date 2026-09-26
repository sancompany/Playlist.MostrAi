> **HISTÓRICO — não é a regra atual.** Documento de antes da reestruturação
> para o MVP (26/09/2026, versão 2.0.0). V1, `/hello`, OTA, Device Owner,
> kiosk, painel técnico, provisionamento por JSON/pendrive/ADB, `baseUrl` e
> rotação variáveis **não existem mais** no Player. O protocolo em vigor é
> `sancompany/MostrAi` → `docs/player-mvp-contract.md`; o estado atual está
> em `README.md` e `docs/funcional.md`. Mantido só pelo histórico de bugs e
> decisões.

# Fechamento final para produção — Mostraí Player

25/09/2026. Rodada de fechamento pedida pelo dono, depois do merge do
[PR #2](https://github.com/sancompany/Playlist.MostrAi/pull/2) e da
auditoria de confiabilidade (`docs/auditoria-confiabilidade-2026-09-23.md`).
Nenhuma feature nova nesta rodada — só compatibilidade, build/release,
documentação e o levantamento do que falta.

**Limite declarado desta rodada:** este repositório não altera nem lê
`sancompany/mostrai` (backend) — regra do próprio README ("Projeto separado
do backend... este repositório não altera o backend"). A seção 2 audita o
contrato Player↔backend comparando `docs/player-v2-contract.md` (escrito
para descrever exatamente o que o Player implementa) contra o código real
do Player. O código do backend em si não foi lido nesta sessão; a
compatibilidade real só é confirmada no teste físico de proof-of-play
(`docs/checklist-fisico-producao.md`, item 10).

---

## 1. Snapshot inicial

| Item | Valor |
|---|---|
| Branch | `main` |
| HEAD (início desta rodada) | `28bc93d` |
| `origin/main` | `28bc93d` (idêntico — working tree limpa) |
| Último PR | [#2](https://github.com/sancompany/Playlist.MostrAi/pull/2), mesclado (squash) |
| CI no HEAD | verde — [run 35933942145](https://github.com/sancompany/Playlist.MostrAi/actions/runs/35933942145) |
| `versionName` / `versionCode` | `1.0.0` / `2` |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 26 / 35 |
| Build types | `debug` (assinatura de depuração), `release` (sem `signingConfig` até existir `keystore.properties`) |
| Testes (baseline) | 283, 0 falhas |
| Documentação | atualizada até a auditoria de 23/09 |

Nada mudou no código nesta rodada — o repositório já estava no estado
pós-auditoria. O trabalho desta rodada foi verificação e fechamento, não
correção.

---

## 2. Compatibilidade com o backend Mostraí atual

Cada endpoint chamado pelo Player foi conferido contra a seção
correspondente do contrato:

| Chamada no código (`MostraiApi.kt`) | Seção do contrato |
|---|---|
| `GET /playlist/{dispositivoId}` | §6 Playlist |
| `POST /player/{dispositivoId}/played` (lote e legado) | §9 Proof-of-play |
| `POST /player/{dispositivoId}/heartbeat` | §4 |
| `POST /player/{dispositivoId}/hello` | §3 |
| `GET /player/{dispositivoId}/config` | §5 |
| `POST /player/provisionar` | §2.1 |

Pontos específicos da lista de auditoria do pedido:

- **`janelaHora`** → `janelaId`/`janelaInicio`/`janelaFim` no envelope da
  playlist (`Playlist.kt`, `PlaylistJson.kt`) — nome diferente do que a
  lista usa, mesmo conceito, documentado no contrato §6.4.
- **Vídeo institucional tratado como mídia normal** (pedido, seção 18) —
  confirmado em código: `tocarItemAtual`/`mostrarVideo` decidem por
  `url != null`, sem checar a flag `institucional`. Sem lógica duplicada.
- **Tela sem mídia ≠ vídeo institucional** (pedido, seção 19) — confirmado:
  `estadoInstitucional()` só retorna `ERRO_CARREGAR`/`NAO_PROVISIONADO`
  quando `ultimaOrigemFetch == Origem.INSTITUCIONAL` (nem servidor nem
  cache tinham playlist usável) ou aparelho sem credencial — nunca como
  substituto do item institucional que o próprio backend manda.
- **V1 fallback** — todo endpoint V2 degrada num 404 (`ResultadoHttp.NaoEncontrado`
  → `backendV2Disponivel = false`), sem quebrar o ciclo. Testado em
  `SincronizacaoV2Test`, `CicloDeVidaTest`, `InstalacaoTest` (que roda
  inteiro contra um backend V1 simulado).

Nenhuma incompatibilidade de contrato encontrada nesta rodada. O que a
auditoria de 23/09 já tinha corrigido (rotação de credencial julgando só a
requisição que levou a chave nova, config antiga não sobrescrever a nova,
heartbeat 2xx não-JSON não apagar OTA pronto, entre outros) continua
coberto pelos mesmos testes de regressão.

---

## 3–23. Verificação técnica (proof-of-play, cache, offline, tempo, reboot,
heartbeat, credenciais, PIN, kiosk, OTA, vídeo, observabilidade, crash)

Todos esses temas já passaram por 19 ciclos de auditoria adversarial mais
10 rodadas de acompanhamento (Auditoria A–J,
`docs/auditoria-confiabilidade-2026-09-23.md`), convergindo em duas rodadas
completas sem bug novo. Não repetidos aqui inteiros — repetir seria a
"auditoria interminável" que esta rodada existe para evitar. Resumo do que
já está garantido, com o teste que prova cada um:

| Tema | Garantido por | Teste |
|---|---|---|
| Não confirma ao iniciar | `FilaProofOfPlay.registrarInicio` não marca `terminadoEm` | `FilaProofOfPlayTest` |
| `execucaoId` estável, retry não duplica | UUID único, nunca regerado | `FilaPerdasTest#retentativa leva o mesmo execucaoId` |
| Reboot não perde confirmação pendente | fila em SQLite, sobrevive a reinício | `AtualizadorPersistenciaTest`, `PersistenciaTest` |
| Offline mantém fila, reconexão envia | callback de conectividade dispara `tentarEnviar()` e rebusca playlist | `CicloDeVidaTest#rede que volta rebusca…` |
| `contentHash`/SHA-256 conferido antes do cache | `CacheMidia.baixarPara` | `CacheMidiaHashTest` |
| Mídia corrompida não é servida | hash divergente nunca cai para URL remota, silêncio de 30 min | `CacheConcorrenciaTest` |
| Playlist antiga não sobrescreve nova | busca serializada (`buscandoPlaylist` + pedido acumulado) | `CicloDeVidaTest` (BUG-002) |
| Config antiga não sobrescreve nova | `sincronizarConfigSeNecessario` `@Synchronized` | `ConfigConcorrenciaTest` |
| Relógio monotônico, não de parede | `RelogioJanela` (`SystemClock.elapsedRealtime`), âncora invalidada por `BOOT_COUNT` | `PlaylistCacheTest` |
| Retomada após reboot | posição por tempo na janela (`PosicaoNaPlaylist`), nunca índice salvo | `PosicaoNaPlaylistTest` |
| Heartbeat só quando existe de verdade | disparado no boot e a cada 5 min; sem heartbeat, sem "operando" no admin | contrato §4 |
| Device ID não é segredo, chave nunca loga inteira | `DiarioBordo.sanitizar`, `PainelActivity.resumirChave/resumirServidor` | `DiarioBordoTest`, `PainelTest` |
| PIN: rate limit, lockout, nunca em log | `ConfigAparelho.registrarPinErrado` (backoff exponencial) | `PinRateLimitTest` |
| Kiosk: BACK não sai, watchdog reabre | `PlayerActivity.onKeyDown`, `Watchdog` + `BootReceiver` | `SegurancaLocalTest`, `BootReceiverTest` |
| OTA não instala APK não autorizado | SHA-256 + `applicationId` + `versionCode` conferidos antes de promover o arquivo | `AtualizadorPersistenciaTest`, `AtualizadorConcorrenciaTest` |
| Crash/ANR: SQLite não derruba o app | `DiarioBordo`/`FilaProofOfPlay` nunca lançam `SQLiteException` | `PersistenciaTest` |
| Watchdog não cria loop de reboot | backoff exponencial (2→32 min), tolerância de 5 min antes de agir | `kiosk/Watchdog.kt`, `CicloDeVidaTest` |

Spot-check extra desta rodada (scan mecânico, não achou nada novo):
nenhum `Log.*` imprime chave, token ou senha — só status e chaves de cache
(hash de conteúdo, nunca segredo).

---

## 4. Dependências

| Biblioteca | Versão |
|---|---|
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Gradle | 8.14.3 |
| Media3 (ExoPlayer) | 1.4.1 |
| AndroidX Lifecycle | 2.8.6 |
| Coroutines | 1.9.0 |
| Robolectric (teste) | 4.13 |

Nenhuma desatualizada a ponto de preocupar; nenhuma vulnerabilidade
conhecida identificada. Não atualizadas nesta rodada — sem necessidade.

---

## 5. Build e release

```
./gradlew clean assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
```

| Item | Resultado |
|---|---|
| Testes | **283, 0 falhas** |
| `lintDebug` | 0 erros, 30 avisos (todos pré-existentes e classificados — ver auditoria de 23/09) |
| `lintRelease` | 0 erros, 23 avisos |
| `assembleDebug` | verde |
| `assembleRelease` | verde — **mas sai sem assinatura de produção** |
| APK debug | `app-debug.apk`, 8.471.384 bytes, SHA-256 `3e0e47c7e66137747a6bb869d60231c1490a55878415ba69c1943bb7cc1d88c5` |
| APK release (não assinado) | `app-release-unsigned.apk`, 7.051.803 bytes, SHA-256 `55750cad6ad5e8dfcbdc56e41050a55444f5f855f9f0c9d1244bbb75632a2f3c` |
| `apksigner verify` no release | **DOES NOT VERIFY** — `Missing META-INF/MANIFEST.MF` (esperado: sem `keystore.properties`, o Gradle não assina) |

---

## 6. BLOCKER — keystore de release

**Único bloqueador que depende só de você.** Sem ele, nenhum APK de
produção pode ser gerado, e a debug build **não pode** ir para uma TV real
(assinatura de depuração não permite atualização remota depois — o Android
recusa troca de assinatura; a única saída seria desinstalar, perdendo
identidade da tela e a fila de comprovantes inteira).

O que fazer — passo a passo já documentado em `RUNBOOK.md`, "Chave de
assinatura":

1. Gerar a chave (fora deste repositório):
   ```sh
   keytool -genkeypair -v -keystore mostrai-release.jks \
     -alias mostrai -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Criar `keystore.properties` na raiz do repositório (já no `.gitignore`,
   nunca vai para o Git):
   ```properties
   storeFile=/caminho/absoluto/para/mostrai-release.jks
   storePassword=...
   keyAlias=mostrai
   keyPassword=...
   ```
3. Guardar o `.jks` em pelo menos dois lugares, um deles offline. Guardar
   as senhas separadas do arquivo. **Perder a chave é irreversível** — a
   frota inteira fica sem caminho de atualização remota, para sempre.

Assim que existir, `./gradlew assembleRelease` sai assinado automaticamente
— não precisa de mais nada de código.

**Não é seguro eu gerar essa chave por você.** Gerar e nunca ver a chave
de novo (guardá-la só localmente, nesta sessão efêmera) seria pior que não
ter chave nenhuma — ela se perderia junto com o container ao fim da sessão.
Isso precisa ser gerado e guardado por você, em local persistente e sob seu
controle.

---

## 7. Physical gates

Tudo que não pode ser provado sem a TV real está em
`docs/checklist-fisico-producao.md` (15 itens, PASS/FAIL). Os dois
obrigatórios antes de qualquer cliente real:

- **Item 10 — proof-of-play ponta a ponta real**: backend programa →
  TV toca → confirmação chega → dashboard reflete.
- **Item 14 — OTA real**: instalar N, publicar N+1, confirmar que atualiza
  sem perder credencial nem fila.

---

## 8. Resíduo aceito (não bloqueia)

Da auditoria de 23/09 (`docs/auditoria-confiabilidade-2026-09-23.md`,
seções 7–8), sem mudança nesta rodada — decisões de produto do dono, ou
dependentes só de hardware:

- Duração mínima de exibição para contar comprovante (RSK-008).
- Primeira exibição de um criativo novo espera o download inteiro
  (RSK-001).
- Prazo de segurança do vídeo de abertura sem decoder travando (RSK-005).
- Relógio de parede da TV depois de uma queda de energia (RSK-006).
- Backend respondendo `400` para tudo põe a fila inteira em quarentena
  permanente (RSK-007) — aviso já no checklist do backend.
- Device Owner: implementado no código, mas sem `DeviceAdminReceiver`
  declarado (nada a habilitar ainda) — há relato de falha em TV TCL; o
  player já opera sem ele.

Nenhum destes é CRITICAL, HIGH nem bloqueador operacional — todos
documentados, nenhum afeta reprodução, proof-of-play, segurança, update,
boot ou recuperação por si só.

---

## 9. Saída final

| # | Item | Valor |
|---|---|---|
| 1 | SHA inicial | `28bc93d` |
| 2 | SHA final | (commit desta rodada — documentação) |
| 3 | `versionName` | `1.0.0` |
| 4 | `versionCode` | `2` |
| 5 | Testes | 283, 0 falhas |
| 6 | CI | verde |
| 7 | Build debug | verde |
| 8 | Build release | verde, **não assinado** |
| 9 | Assinatura release | **ausente — BLOCKER** (seção 6) |
| 10 | Compatibilidade com backend | contrato ↔ código Player: sem incompatibilidade encontrada; código do backend em si fora de escopo (seção acima) |
| 11 | Proof-of-play | validado em código; teste físico ponta a ponta obrigatório (checklist item 10) |
| 12 | Cache | validado em código |
| 13 | Offline | validado em código |
| 14 | Heartbeat | validado em código |
| 15 | OTA | validado em código; teste físico obrigatório (checklist item 14) |
| 16 | Kiosk | implementado; Device Owner é `PHYSICAL_GATE` |
| 17 | Crash/watchdog | validado em código |
| 18 | Dependências | atuais, sem vulnerabilidade conhecida |
| 19 | Critical aberto | 0 |
| 20 | High aberto | 0 |
| 21 | Medium residual | 0 novo (resíduo de produto/hardware já documentado, seção 8) |
| 22 | Low residual | 0 novo |
| 23 | Artefato release | `app-release-unsigned.apk` — não distribuível como está |
| 24 | SHA-256 APK | debug `3e0e47c7…d88c5`; release (não assinado) `55750cad…632a2f3c` |
| 25 | Lista PHYSICAL_GATE | os 15 itens de `docs/checklist-fisico-producao.md` |
| 26 | Checklist físico final | `docs/checklist-fisico-producao.md` |
| 27 | Blockers que só dependem do dono | (a) gerar e guardar o keystore de release; (b) rodar os 15 testes físicos, com os itens 10 e 14 obrigatórios |
| 28 | **Veredito** | **PLAYER TECNICAMENTE PRONTO — AGUARDANDO TESTE FÍSICO** |

Depois que o dono gerar o keystore, eu gero e assino o release final. Depois
dos testes físicos devolvidos com PASS nos itens obrigatórios (e sem FAIL
bloqueador nos demais), o veredito passa a
**PLAYER PRONTO PARA PRODUÇÃO**.
