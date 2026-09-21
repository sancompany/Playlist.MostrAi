# Mostraí Player

Aplicativo Android TV nativo da rede Mostraí (mídia digital fora de casa, Matão-SP).
Toca a playlist da tela em laço e devolve ao servidor o comprovante de que cada
anúncio realmente passou.

Projeto separado do backend (`sancompany/mostrai`). Este repositório não altera
o backend.

Projeto da San & Co. — segue a esteira do plugin `san-co` (skill `leis`).
Estado atual, decisões e pendências vivem em `CLAUDE.md`, não neste README.

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
5. **Retomada por posição temporal, nunca por índice salvo** (item 7.1,
   fechado com o GPT em 21/09/2026). Depois de um reinício, o app calcula
   onde a programação deveria estar (`janelaInicio` + `servidorAgora` +
   tempo monotônico local) em vez de continuar do último índice tocado — que
   distorceria a distribuição da hora e favoreceria sistematicamente o
   começo da lista, o mesmo bug do player web (seção 5). Um item pego no
   meio é pulado inteiro: nunca há seek, porque uma exibição parcial não
   pode virar comprovante. Tolerância de 500 ms na borda inicial para
   diferenças pequenas de sincronização. Sem `servidorAgora` confiável (por
   exemplo, logo após um reboot real, que reinicia o relógio monotônico), o
   app não improvisa com o índice salvo: espera sincronizar ou cai na tela
   institucional.

## Estado atual — MVP completo, fatias 1 a 3

O que já está no APK:

**Fatia 1 — player**

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

**Fatia 2 — rede e comprovante**

- Cliente HTTP próprio (`HttpURLConnection`, sem dependência externa) que lê
  `/playlist` nas duas formas do contrato (seção 6.6) e escolhe modo novo ou
  degradado automaticamente.
- Busca a playlist a cada 15 min, mais uma busca extra na virada da hora com
  o atraso determinístico da chave do aparelho. Heartbeat a cada 5 min.
- Última playlist recebida com sucesso fica em cache local — o app continua
  tocando offline se a rede cair.
- Retomada por posição temporal (decisão 5 acima), com reancoragem pelo
  `itemProgramacaoId` — não pelo índice bruto do array — quando a playlist é
  atualizada dentro da mesma janela.
- **Fila durável de proof-of-play em SQLite** (sem Room: esquema pequeno,
  `SQLiteOpenHelper` já entrega a durabilidade que a decisão 6.4 pede). A
  linha nasce com o `execucaoId` antes do `play()`, só fica elegível para
  envio quando ganha `terminadoEm` no `STATE_ENDED`, e só sai da fila nos três
  casos fechados na seção 6.5 — nunca por timeout, `5xx` ou reinício do app.
  Envio em lote de até 50, backoff exponencial com jitter (5s → 30min, teto,
  nunca desistência), fila limitada a 5.000 linhas com contador de perda
  visível no painel.
- Painel de manutenção agora mostra o modo de contrato, a origem da última
  playlist (servidor/cache/institucional), erro do aparelho, e o estado da
  fila de proof-of-play (pendentes e perdas).
**Fatia 3 — cache de mídia**

- Cache local por `criativoId` (bloco 4 do MVP): a imutabilidade
  `criativoId → url` do contrato novo permite usar o `criativoId` como chave
  sem revalidar nada; em modo degradado cai para hash da própria URL.
- Pré-aquecimento sequencial a cada playlist nova — baixa o que falta em
  segundo plano, sem atrasar a reprodução em andamento nem saturar a
  internet de um comércio pequeno.
- Teto de tamanho simples (1GB, descarte do mais antigo) — sem LRU
  sofisticado na v1.
- Download que falha nunca bloqueia a exibição: cai para tocar direto da
  URL remota.

**Testes e revisão**

39 testes automatizados (`./gradlew testDebugUnitTest`), cobrindo as duas
formas do contrato, a regra de reposicionamento, o cache e — com
Robolectric, SQLite real, sem emulador — o ciclo de vida completo da fila
de proof-of-play. CI (`.github/workflows/ci.yml`) roda build + testes a
cada push e pull request.

Todos os blocos do MVP (seção 3 do escopo original) estão implementados. O
que falta para o projeto avançar na esteira san-co é a verificação em
hardware real (`docs/pendencias.md`, "Só o dono faz") — esta sessão não tem
acesso a um aparelho Android TV nem a um emulador viável.

### Gesto do painel

**Três acionamentos do botão OK/CENTER em até 3 segundos.** É o equivalente de
controle remoto aos cinco toques num canto que o player web usa hoje — número
adaptado para o controle, já aprovado pelo dono.

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

## Gerar um APK já configurado por tela

Quando você já sabe, antes de gravar o pendrive, qual `dispositivoId` e
`chaveAparelho` vão para qual TV, não precisa de `adb` depois de instalar: dá
para embutir a configuração no próprio APK e ele se provisiona sozinho no
primeiro boot.

1. Copie `dispositivos/exemplo.json.example` para `dispositivos/<nome-da-tela>.json`
   e preencha com os dados reais daquela tela (vêm do cadastro no admin do
   Mostraí). Esses arquivos **nunca são versionados** — `.gitignore` já
   cobre `dispositivos/*.json` (só o `.example` fica no Git). A chave
   continua sendo revogável no admin se algum dia esse APK vazar; não é
   diferente do risco de qualquer aparelho perdido.

   ```json
   {
     "dispositivoId": "id-da-tela-no-cadastro-do-admin",
     "chaveAparelho": "chave-revogavel-emitida-no-admin",
     "baseUrl": "https://exemplo.com/api",
     "pin": "4821",
     "margemVmin": 2.5
   }
   ```

2. Compile passando o arquivo:

   ```sh
   ./gradlew assembleDebug -PconfigDispositivo=dispositivos/loja-centro.json
   ```

3. **Renomeie o APK antes de compilar o próximo**, porque a saída tem sempre
   o mesmo nome:

   ```sh
   cp app/build/outputs/apk/debug/app-debug.apk mostrai-loja-centro.apk
   ```

4. Repita os passos 1–3 para cada tela. No fim você tem um `.apk` por
   aparelho, cada um pronto para instalar por pendrive sem nenhum passo de
   `adb` depois — o app lê a configuração embutida no primeiro boot e já
   sobe funcionando.

Sem `-PconfigDispositivo`, o build volta a ser exatamente o de sempre (os
cinco campos ficam vazios, nada muda) — é seguro rodar `./gradlew
assembleDebug` normalmente a qualquer momento.

**O que isso não resolve**: o provisionamento verdadeiramente "sem
intervenção nenhuma no campo" (item 4 em "Em aberto") continua em aberto —
este caminho pede que alguém decida, num computador, qual tela é qual antes
de gravar o pendrive. Para quem já opera assim (uma pessoa prepara os APKs,
outra só troca o pendrive na loja), resolve completamente.

## Instalar e provisionar em bancada (sem configuração embutida)

Se preferir instalar o APK genérico e configurar depois (por exemplo, para
testar rápido sem preparar um arquivo por tela):

```sh
adb install -r app-debug.apk

# PROVISÓRIO: só para bancada. Sempre sobrescreve, mesmo por cima de uma
# configuração já embutida no build — é o caminho de depuração.
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

1. Ciclo de vida quando o Android mata o app mesmo assim.
2. Atualização remota (OTA) em Android TV 8 sideloaded.
3. PIN universal × PIN por tela do admin.
4. Provisionamento **de campo** — sem ninguém decidir de antemão qual APK vai
   para qual tela (ex.: escanear um QR code no primeiro boot). "Gerar um APK
   já configurado por tela" (seção acima) resolveu o caso em que alguém já
   sabe essa relação antes de gravar o pendrive; o caso genérico — tela
   chega sem ninguém ter decidido nada ainda — continua em aberto.

Retomada de índice depois de reinício (item que era o nº 1 desta lista) foi
fechada com o GPT em 21/09/2026 — ver decisão 5 acima.
