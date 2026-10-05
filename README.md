# Mostraí Player

Aplicativo Android TV nativo da rede Mostraí (mídia digital fora de casa, Matão-SP).
Toca a playlist da tela em laço e devolve ao servidor o comprovante de que cada
anúncio realmente passou.

Projeto separado do backend (`sancompany/MostrAi`). Este repositório não altera
o backend. Projeto da San & Co. — segue a esteira do plugin `san-co` (skill
`leis`). Estado atual, decisões e pendências vivem em `CLAUDE.md`.

## O que o Player faz (3.0.0 — V1 de produção)

```
PROVISIONAR → RECEBER PLAYLIST → REPRODUZIR → CACHEAR → AGUENTAR QUEDA DE REDE
            → CONFIRMAR PROOF-OF-PLAY → ENVIAR HEARTBEAT → RECEBER CONFIG MÍNIMA
            → SAIR COM PIN → SE RECUPERAR (boot + watchdog)
```

E nada além disso. Tela por tela em `docs/funcional.md`.

**A Mostraí é online-first.** Offline é tolerância a interrupções
temporárias, não modo normal de operação: ponto fixo usa a internet do
estabelecimento; ponto móvel, hospedagem e eventos sem internet local usam
dados móveis (hotspot, roteador 4G/5G). Numa queda, a tela segue com a
programação já autorizada até o fim da janela e depois com o institucional;
comprovantes e tempo operacional ficam guardados até a confirmação.
Operação comercial por dias sem conexão não existe na V1 (decisão de
05/10/2026).

## Contrato com o backend

Fonte oficial: **`sancompany/MostrAi` → `docs/player-mvp-contract.md`**. Seis
rotas, e só elas:

| Método | Caminho |
|---|---|
| POST | `/player/provisionar` |
| GET | `/playlist/:dispositivoId` |
| POST | `/player/:dispositivoId/played` |
| POST | `/player/:dispositivoId/heartbeat` |
| GET | `/player/:dispositivoId/config` |
| POST | `/player/:dispositivoId/operacao` (tempo operacional em segmentos — Ponto Móvel) |

Conferência campo a campo: `docs/player-mvp-matriz.md`.

## Valores fixos

Constantes em `app/src/main/java/br/com/mostrai/player/Produto.kt`. Não há
preferência, JSON, pendrive, ADB nem campo do backend que os mude.

```
BASE_URL      = https://mostrai.sancocore.com.br
ROTATION      = 90
HEARTBEAT     = 15s
POP_RETENTION = até a confirmação do servidor (nunca por idade)
PROVISIONING  = M-xxxx + XXXX-XXXX
```

Se o primeiro teste físico mostrar a imagem de ponta-cabeça, a correção é
**uma build nova** com `ROTACAO_GRAUS = 270` — nunca rotação configurável.

## Alvo

| | |
|---|---|
| Hardware de referência | SEMP TCL 32S6500S |
| Android | 8.0 Oreo — API 26 |
| `minSdk` / `targetSdk` | 26 / 36 |
| `compileSdk` | 36 |
| Versão | 3.0.0 (`versionCode 4`) |
| `applicationId` | `br.com.mostrai.player` |
| Distribuição | sideload por pendrive, APK de release assinado com a chave definitiva |

O piso é a TCL (Android 8); o alvo é o Android TV mais novo (16). O que muda
por versão de Android — e por que `SYSTEM_ALERT_WINDOW` — está em
`docs/android-modernizacao.md` e no ADR `docs/adr/0001-target-sdk-moderno.md`.
Hardware: `docs/hardware/tcl-32s6500s.md`.

## Regras que não se negociam

- **Nunca usuário e senha na TV.** A tela se autentica por chave de aparelho
  revogável, obtida uma vez com o ID da tela + código de instalação.
- **Nenhum segredo versionado.** Keystore, senhas e credenciais ficam fora do
  Git. A chave do aparelho nunca aparece em tela, log ou diário.
- **A tela não decide nada sozinha.** Hora, ordem e o que conta são do servidor.
- **O relógio da TV não decide negócio.** Posição na hora vem de
  `janelaInicio`/`servidorAgora` + tempo monotônico.
- **Só conclusão real conta.** Proof-of-play apenas em `STATE_ENDED`.
- **O manifesto não declara `HOME`.** O instalador da TCL recusa
  (`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`).

## Instalar numa TV

1. Na TV: suspensão automática, modo loja e protetor de tela desligados
   (`RUNBOOK.md`, "Ajustes da TV na instalação"). Em TV Android 10+,
   conceder "Exibir sobre outros apps" ao Mostraí Player depois de instalar.
2. Copiar o APK de release (`Mostrai-Player-3.0.0-release.apk`) para um
   pendrive e instalar pelo gerenciador de arquivos da TV. Abrir **Mostraí
   Player**.
3. No admin (Rede → Ponto → Tela): copiar o **ID da tela** (`M-0235`) e gerar
   o **código de instalação** (`XXXX-XXXX`, vale 30 min, uso único).
4. Na TV: digitar os dois com o controle e apertar **CONECTAR**.

Pronto. Margens, horário e PIN de saída chegam sozinhos pelo heartbeat.

**Sair do app:** VOLTAR no controle → PIN de saída (definido no admin, em
Rede). Sem PIN definido, não há saída autorizada. Reabrir o app (ou
reiniciar a TV) volta à operação normal.

**Reinstalar / trocar de tela:** revogar o Player no admin (a TV volta à tela
de instalação em até 15 s) e gerar um código novo.

## Compilar e testar

JDK 21 (o Robolectric roda o SDK 36 só em JDK 21+; o bytecode é JVM 17) e
Android SDK (platform 36, build-tools 36.0.0).

```sh
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
scripts/varrer-segredos.sh                 # nada de chave/senha no Git
scripts/verificar-apk.sh app/build/outputs/apk/debug/app-debug.apk
scripts/release-candidato.sh               # candidato + REGISTRO.txt
```

- `app/build/outputs/apk/debug/app-debug.apk` — chave de debug e HTTP só
  para `127.0.0.1`. Bancada; **não** é produção.
- `app/build/release-candidato/` — o release e o registro dele
  (`docs/release-producao.md`). Sem `keystore.properties` sai
  `…-NAO-ASSINADO.apk` e o registro diz "NÃO É PRODUÇÃO".

O CI (`.github/workflows/ci.yml`) roda a varredura de segredos, os dois
builds, a conferência do APK, lint e os testes (Robolectric, com a política
de retorno conferida nos níveis 26, 29, 31, 34 e 36).

## Assinar o release

Passo a passo em `RUNBOOK.md`, "Chave de assinatura". Resumo: o dono gera o
`.jks` fora do repositório e cria `keystore.properties` na raiz (já no
`.gitignore`). Nunca commitar `.jks`, `.keystore` nem senha.

Toda versão futura precisa da **mesma** chave: o Android recusa atualizar por
cima de outra assinatura, e desinstalar apaga a credencial da tela e a fila de
proof-of-play. Uma TV instalada com a chave de debug só troca de versão com
desinstalação + reprovisionamento.

## Histórico

A 3.0.0 (05/10/2026) é a V1 de produção: target 36, tempo operacional em
segmentos (contrato do backend #114), institucional de reserva como `IDLE`,
fila de comprovantes e cache endurecidos contra relógio e disco hostis,
release assinado com registro. Produção começa do zero nela — nada das
instalações de teste é migrado.

A 1.x tinha V1, `/hello`, OTA, Device Owner, painel técnico, provisionamento
por JSON/pendrive/ADB, `baseUrl` e rotação configuráveis. Tudo isso saiu na
2.0.0. Os documentos daquela fase estão em `docs/historico/`; os bugs
resolvidos continuam em `docs/erros/`.
