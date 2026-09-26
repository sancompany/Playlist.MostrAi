# Mostraí Player

Aplicativo Android TV nativo da rede Mostraí (mídia digital fora de casa, Matão-SP).
Toca a playlist da tela em laço e devolve ao servidor o comprovante de que cada
anúncio realmente passou.

Projeto separado do backend (`sancompany/MostrAi`). Este repositório não altera
o backend. Projeto da San & Co. — segue a esteira do plugin `san-co` (skill
`leis`). Estado atual, decisões e pendências vivem em `CLAUDE.md`.

## O que o Player faz (MVP 2.0.0)

```
PROVISIONAR → RECEBER PLAYLIST → REPRODUZIR → CACHEAR → FUNCIONAR OFFLINE
            → CONFIRMAR PROOF-OF-PLAY → ENVIAR HEARTBEAT → RECEBER CONFIG MÍNIMA
            → SAIR COM PIN → SE RECUPERAR (boot + watchdog)
```

E nada além disso. Tela por tela em `docs/funcional.md`.

## Contrato com o backend

Fonte oficial: **`sancompany/MostrAi` → `docs/player-mvp-contract.md`**. Cinco
rotas, e só elas:

| Método | Caminho |
|---|---|
| POST | `/player/provisionar` |
| GET | `/playlist/:dispositivoId` |
| POST | `/player/:dispositivoId/played` |
| POST | `/player/:dispositivoId/heartbeat` |
| GET | `/player/:dispositivoId/config` |

Conferência campo a campo: `docs/player-mvp-matriz.md`.

## Valores fixos

Constantes em `app/src/main/java/br/com/mostrai/player/Produto.kt`. Não há
preferência, JSON, pendrive, ADB nem campo do backend que os mude.

```
BASE_URL      = https://mostrai.sancocore.com.br
ROTATION      = 90
HEARTBEAT     = 15s
POP_RETENTION = 7 dias
PROVISIONING  = M-xxxx + XXXX-XXXX
```

Se o primeiro teste físico mostrar a imagem de ponta-cabeça, a correção é
**uma build nova** com `ROTACAO_GRAUS = 270` — nunca rotação configurável.

## Alvo

| | |
|---|---|
| Hardware de referência | SEMP TCL 32S6500S |
| Android | 8.0 Oreo — API 26 |
| `minSdk` / `targetSdk` | 26 / 26 |
| `compileSdk` | 35 |
| `applicationId` | `br.com.mostrai.player` |
| Distribuição | sideload por pendrive |

`targetSdk` fica em 26 de propósito: o app não vai à Play Store e precisa de
auto-boot e execução contínua.

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

1. Copiar o APK para um pendrive e instalar pelo gerenciador de arquivos da TV.
2. Abrir **Mostraí Player**.
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

JDK 17+ e Android SDK (platform 35, build-tools 35.0.1).

```sh
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

- `app/build/outputs/apk/debug/app-debug.apk` — assinado com a chave de
  debug. Serve para teste físico, **não** é produção.
- `app/build/outputs/apk/release/app-release-unsigned.apk` — sem
  `keystore.properties` o release sai sem assinatura, de propósito.

## Assinar o release

Passo a passo em `RUNBOOK.md`, "Chave de assinatura". Resumo: o dono gera o
`.jks` fora do repositório e cria `keystore.properties` na raiz (já no
`.gitignore`). Nunca commitar `.jks`, `.keystore` nem senha.

Toda versão futura precisa da **mesma** chave: o Android recusa atualizar por
cima de outra assinatura, e desinstalar apaga a credencial da tela e a fila de
proof-of-play. Uma TV instalada com a chave de debug só troca de versão com
desinstalação + reprovisionamento.

## Histórico

A 1.x tinha V1, `/hello`, OTA, Device Owner, painel técnico, provisionamento
por JSON/pendrive/ADB, `baseUrl` e rotação configuráveis. Tudo isso saiu na
2.0.0. Os documentos daquela fase estão em `docs/historico/`; os bugs
resolvidos continuam em `docs/erros/`.
