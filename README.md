# Mostraí Player

Aplicativo Android TV nativo da rede Mostraí (mídia digital fora de casa, Matão-SP).
Toca a playlist da tela em laço e devolve ao servidor o comprovante de que cada
anúncio realmente passou.

Projeto separado do backend (`sancompany/mostrai`). Este repositório não altera
o backend.

## Alvo

| | |
|---|---|
| Hardware de referência | SEMP TCL 32S6500S |
| Android | 8.0 Oreo — API 26 (piso real, não suposição) |
| `minSdk` / `targetSdk` | 26 / 26 |
| `compileSdk` | 35 |
| Distribuição | sideload por pendrive |

`targetSdk` fica em 26 de propósito. O app não vai à Play Store e precisa de
auto-boot e execução contínua; subir o `targetSdk` traria restrições de serviço
em background, tipos de foreground service e permissões de notificação que só
atrapalhariam este caso de uso.

## Regras que não se negociam

- **Nunca usuário e senha na TV.** A tela se autentica só por uma chave de
  aparelho revogável, emitida no painel admin.
- **Nenhum segredo versionado.** Chave, token, keystore de assinatura, URL de
  infraestrutura e credencial ficam fora do Git. O keystore de release fica com
  o dono.
- **A tela não decide nada sozinha.** Hora, ordem e o que conta são do servidor.
  O app executa e relata.
- **O relógio da TV não é confiável.** Nenhuma decisão de negócio depende dele.
  Agendamento por tempo monotônico (`SystemClock.elapsedRealtime`), identidade
  de janela e de item vindas do backend.

## Decisões fechadas

1. **Media3 (ExoPlayer) nativo** — sem WebView, sem híbrido.
2. **Só conclusão real conta.** A exibição vira comprovante apenas em
   `STATE_ENDED`. Sem limiar de percentual.
3. **UUID de execução gerado no aparelho**, criado e persistido antes do
   `play()`, com deduplicação obrigatória no backend.
4. **Identidade de janela e de item vêm do backend** (`janelaId`,
   `itemProgramacaoId`), nunca do relógio da TV.

## Estado atual — fatia 1

O que já está no APK:

- Activity única em tela cheia, vídeo mudo, sem barra de sistema, com
  `FLAG_KEEP_SCREEN_ON`.
- Ciclo item a item: cada exibição é preparada, tocada e observada até o
  `STATE_ENDED` dela. **Não** usa a fila interna do ExoPlayer, justamente para
  que cada conclusão seja observável — é o gancho onde o comprovante entra.
- Item institucional desenhado no próprio aparelho, sem baixar nada.
- Auto-boot por `BOOT_COMPLETED` (e `QUICKBOOT_POWERON`, que alguns aparelhos
  usam no lugar).
- Margem de overscan em vmin, para compensar moldura de TV que corta a borda.
- Painel de manutenção protegido por PIN, com teclado numérico na tela
  navegável pelo D-pad — controle de Android TV normalmente não tem numérico.
- Atraso determinístico de 0 a 29 s derivado da chave do aparelho, para as telas
  da rede não baterem no servidor no mesmo segundo na virada da hora.

O que ainda **não** está: rede, cache de mídia e fila durável de proof-of-play.

### Gesto do painel

**Cinco acionamentos do botão OK/CENTER em até 3 segundos.** É o equivalente de
controle remoto aos cinco toques num canto que o player web usa hoje. Proposta —
aguarda o aval do dono.

### PIN

O PIN inicial é `0000` e o painel avisa enquanto ele não for trocado. Não é um
segredo versionado, é valor de fábrica. Como o PIN universal convive com o PIN
por tela do admin é decisão em aberto.

## Compilar

Precisa de JDK 17+ e do Android SDK (platform 35, build-tools 35.0.1).

```sh
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`, assinado com a chave
de debug — suficiente para sideload de teste.

## Instalar e provisionar em bancada

```sh
adb install -r app-debug.apk

# PROVISÓRIO: só para bancada. O provisionamento de campo é decisão em aberto.
adb shell am start -n br.com.mostrai.player/.PlayerActivity \
  -e dispositivoId "<id-da-tela>" \
  -e chaveAparelho "<chave-revogavel>" \
  -e baseUrl "https://<servidor>" \
  -e pin "<pin>"
```

## Contrato com o backend

O contrato novo (envelope com `versaoContrato`, `janelaId`, `itemProgramacaoId`,
`criativoId`, e `POST /played` em lote com `execucaoId`) está sendo implementado
em paralelo no backend. A camada de rede deste app é desenhada para as duas
formas desde o começo: se o `/playlist` responder um array puro, o app opera em
**modo degradado** — toca normalmente, manda `{anuncianteId}` no `played`, e o
painel mostra "servidor em contrato antigo".

Duas garantias que este app depende do backend manter:

- `itemProgramacaoId` deriva da posição na sequência congelada da hora, não do
  índice do array da resposta.
- `criativoId → url` é imutável. Criativo trocado é `criativoId` novo. É isso
  que permite usar `criativoId` como chave de cache de mídia sem revalidar nada.

## Em aberto

1. Retomada de índice depois de reinício (último índice × posição temporal na hora).
2. Ciclo de vida quando o Android mata o app mesmo assim.
3. Atualização remota (OTA) em Android TV 8 sideloaded.
4. PIN universal × PIN por tela do admin.
5. Provisionamento no primeiro boot, sem teclado e sem usuário/senha.
