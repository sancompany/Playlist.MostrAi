# Modernização Android — Player 3.0.0 (V1 de produção)

Levantamento de 05/10/2026. Decisão registrada em
`docs/adr/0001-target-sdk-moderno.md`. Hardware do parque em
`docs/hardware/tcl-32s6500s.md`.

## Resumo

| | Antes (2.0.0) | Agora (3.0.0) |
|---|---|---|
| `minSdk` | 26 | **26** (SEMP TCL 32S6500S, Android TV 8.0) |
| `targetSdk` / `compileSdk` | 26 / 35 | **36 / 36** (Android 16 — o Android TV mais novo que existe) |
| AGP / Gradle | 8.7.3 / 8.14.3 | **8.13.2** / 8.14.3 |
| Kotlin | 2.0.21 | **2.3.21** (`kotlin { compilerOptions }`) |
| Robolectric | 4.13 | **4.16.1** (SDK 36 exige JDK 21) |
| JDK do CI | 17 | **21** (bytecode continua JVM 17) |
| Media3 | 1.4.1 | 1.4.1 (sem mudança — ver "O que ficou para depois") |
| `versionCode` / `versionName` | 3 / 2.0.0 | **4 / 3.0.0** |
| Permissões | 3 | **4** (+ `SYSTEM_ALERT_WINDOW`, justificada abaixo) |

Por que 36 e não 37: o Android 17 (API 37) é estável desde 16/06/2026, mas
**nenhuma TV roda API 37** — o Android TV mais novo é o 16 (API 36), e não
existe Android TV na API 35. Compilar para 37 exige AGP 9 (migração de
DSL, Kotlin embutido), sem ganho para o parque. Fica para um passo
separado.

`targetSdk` só liga comportamentos novos em sistemas **iguais ou mais novos**
que o alvo. Na TCL (API 26) quase nada muda; o que muda é o Player em TVs
Android 10+ — e duas coisas já valiam lá **independentemente do target**.

## O que muda por nível de Android, e o que o Player faz

| Mudança | Vale a partir de | Efeito sem tratar | O que o Player 3.0.0 faz | Prova |
|---|---|---|---|---|
| Bloqueio de abrir Activity do segundo plano (BAL) | Android 10 (API 29), **qualquer target** | `BootReceiver` e o retorno do watchdog são bloqueados em silêncio: a TV liga e fica no launcher; HOME tira o Player da frente para sempre | Declara `SYSTEM_ALERT_WINDOW` ("Exibir sobre outros apps"), a exceção oficial. Concedida na TV (Configurações › Apps › Acesso especial a apps) ou por `adb shell appops set br.com.mostrai.player SYSTEM_ALERT_WINDOW allow`. Sem ela, o bloqueio fica no diário (`RETORNO_BLOQUEADO`) e no bloco técnico do PIN ("Retorno automático BLOQUEADO…"). O Player nunca abre janela de sobreposição — a permissão só serve de exceção ao BAL | `kiosk/PoliticaDeRetorno.kt`, `RetornoPorApiTest` (API 26/29/31/34/36) |
| Alarme exato pede permissão | target 31+, Android 12+ | `setExact` lança `SecurityException`; o retorno de 5 s some sem erro | `setExact` até o Android 11; `set` do 12 em diante. O AlarmManager do AOSP não adia alarme com menos de 10 s — o primeiro retorno (5 s) segue no tempo; os seguintes podem atrasar até 75 % do prazo. **Não** pede `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` | `RetornoPorApiTest` (janela 0 só abaixo do 31) |
| Voltar preditivo | target 36, Android 16 | `KEYCODE_BACK` deixa de chegar a `onKeyDown`: VOLTAR não pediria mais o PIN | VOLTAR passou para `OnBackPressedDispatcher` (`OnBackPressedCallback`), que funciona do 8 ao 16 | `SaidaCicloTest`, `SegurancaLocalTest` |
| Texto puro (HTTP) desligado | target 28, Android 9+ | URL `http://` de mídia ou API falharia | `networkSecurityConfig`: produção só HTTPS com CAs do sistema; o build **debug** libera só `127.0.0.1`/`localhost` (testes) | `res/xml/seguranca_de_rede.xml`, `src/debug/res/xml/`, `scripts/verificar-apk.sh` |
| Transferência entre aparelhos ignora `allowBackup=false` | target 31, Android 12+ | Credencial e fila poderiam ir para outro aparelho numa troca de TV | `dataExtractionRules` exclui tudo de backup em nuvem e de transferência | `res/xml/regras_de_extracao.xml` |
| Aviso "feito para uma versão antiga" | Android 14+ com target < 28 | Diálogo do sistema sobre o Player até alguém apertar OK | Some com target 36 | — |
| Edge-to-edge obrigatório | target 35/36 | Conteúdo sob barras do sistema | O Player esconde as barras com `WindowInsetsControllerCompat` e ocupa a tela inteira (TV normalmente não tem barras) | Só no aparelho |
| Splash do sistema | Android 12+ | Ícone/banner sobre fundo claro antes do vídeo de abertura | `values-v31/themes.xml`: fundo preto | Só no aparelho |
| Orientação/redimensionamento ignorados | target 36, telas ≥ 600 dp | — | Não se aplica à TV (sw540dp); o Player gira o próprio conteúdo (`RotacaoTela`, fixo em 90°) | — |
| `PendingIntent` mutável, `exported`, receivers de contexto | target 31/33/34 | — | Já conforme: `FLAG_IMMUTABLE`, `exported` em todos os componentes, nenhum receiver registrado por contexto | `GuardaMvpTest` |

## O que **não** entrou, e por quê

- **`CATEGORY_HOME` (launcher padrão)**: o instalador da TCL recusa o APK
  ("instalação anormal") — `docs/erros/2026-09-25-…`. Não volta.
- **Serviço em primeiro plano**: não é exceção ao BAL, exige notificação e
  permissão de tipo de serviço, e o Player não tem trabalho de fundo que
  precise dele.
- **Device Owner / lock task**: saiu na 2.0.0; exigiria reset de fábrica e
  `adb` em cada TV.
- **`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`**: `set` atende o retorno de
  5 s sem permissão nova.
- **Câmera, microfone, OTA, BYOD**: fora do escopo (`CONSTRAINTS.md`).
- **Operação offline de vários dias**: cancelada (05/10/2026). A Mostraí é
  online-first; nada de trabalho em segundo plano para pré-carregar dias de
  programação — e, portanto, nenhuma necessidade de serviço em primeiro
  plano ou de tarefa agendada para isso.

## O que ficou para depois

- **AGP 9 / API 37**: quando existir TV com Android 17.
- **Media3 1.10.1+**: traz `StuckPlayerException` (erro depois de 10 s
  parado em READY), wake lock ligado por padrão (+`WAKE_LOCK` no manifesto
  mesclado) e uma regressão de troca de codec abaixo do API 30 entre
  vídeos de fps diferente (1.9.1–1.10.0). Pede teste na TCL antes.
  Ficou 1.4.1.

## Limites desta verificação

- Robolectric roda a lógica do Player nos níveis 26, 29, 31, 34 e 36; **não**
  roda o firmware de nenhum fabricante. "Funciona na TCL" e "Android 16
  validado" só depois do checklist físico (`docs/checklist-fisico-producao.md`).
- Este ambiente não tem KVM: nenhum emulador foi executado (`EMULATOR_PASS`
  vazio no relatório).
- Não confirmados: comportamento real do VOLTAR em TVs Android 16 de
  fabricante; caminho do menu "Exibir sobre outros apps" na TCL e no Google
  TV; se o firmware da TCL tem "Safety Guard" bloqueando autostart.
