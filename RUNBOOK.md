# RUNBOOK — Mostraí Player

Como operar, reverter e restaurar o app instalado numa TV. Sem serviço
hospedado deste lado: quase tudo aqui é local ao aparelho. O protocolo com o
servidor é `sancompany/MostrAi` → `docs/player-mvp-contract.md`.

## Operar

**Instalar e provisionar**: `README.md`, "Instalar numa TV". ID da tela
(`M-0235`) + código de instalação (`XXXX-XXXX`, 30 min, uso único) gerado no
admin. Só o APK de release assinado com a chave definitiva vai para TV de
cliente (`docs/release-producao.md`).

**Ajustes da TV na instalação** (uma vez por TV, antes de provisionar):
suspensão automática ("Entrar no modo de suspensão") **desligada**, modo
loja/"Ambiente: Loja" desligado, protetor de tela desligado, Wi-Fi 2,4 GHz
WPA2-AES ou cabo (`docs/hardware/tcl-32s6500s.md`). Em TV **Android 10 ou
mais nova**: conceder "Exibir sobre outros apps" ao Mostraí Player
(Configurações › Apps › Acesso especial a apps) — sem isso o Player não volta
sozinho depois de HOME, crash ou religar a TV, e o bloco técnico do PIN diz
"Retorno automático BLOQUEADO". Na TCL (Android 8) não é preciso. Em
bancada, o mesmo por `adb`:

```sh
adb shell appops set br.com.mostrai.player SYSTEM_ALERT_WINDOW allow
```

**Ver o estado de uma tela**: no admin (Aguardando instalação, Operando, Fora
do horário, Sem sinal, Erro do Player). O Player manda `estado`, `erro`,
`fila` e a config aplicada a cada 15 s (fonte única do intervalo:
`Produto.INTERVALO_HEARTBEAT_MS`; o servidor considera "sem sinal" depois
de 2 min). Na TV, o bloco técnico abaixo do pedido de PIN (VOLTAR) mostra
versão, aparelho, Android, estado, conexão, programação válida até, mídia
no aparelho, espaço livre, comprovantes e tempo operacional aguardando
envio, último erro e se o retorno automático está bloqueado — nunca chave,
token, código ou URL.

**Mudar margens, horário ou PIN**: no admin. A TV aplica em até ~15 s (próximo
heartbeat → `GET /config`), sem reiniciar.

**Sair do app na TV**: VOLTAR → PIN de saída. O watchdog não reabre. Para
voltar, abrir o app (ou reiniciar a TV).

**Ver logs em bancada** (TV com depuração ADB ligada — só diagnóstico, não é
caminho de provisionamento):

```sh
adb logcat --pid=$(adb shell pidof -s br.com.mostrai.player)
```

Tags: `MostraiPlayer`, `MostraiApi`, `FilaProofOfPlay`, `PlaylistRepositorio`,
`Watchdog`, `CacheMidia`, `RegistroOperacional`. A chave do aparelho, o
código de instalação e os cabeçalhos nunca aparecem nos logs
(`SegredoForaTest`). O release não é `debuggable`: `pidof`/`logcat` por pid
só no debug; no release, `adb logcat -s` pelas tags.

## Conectividade

**A Mostraí é online-first.** Offline é tolerância a interrupções
temporárias, não modo normal de operação: ponto fixo usa a internet do
estabelecimento; ponto móvel, hospedagem e eventos sem internet local usam
dados móveis (hotspot, roteador 4G/5G). Numa queda, a tela segue com a
programação já autorizada até o fim da janela e depois com o institucional;
comprovantes e tempo operacional ficam guardados até a confirmação.
Operação comercial por dias sem conexão não existe na V1 (decisão de
05/10/2026). Ponto sem internet estável: instalar um roteador 4G/5G ou
hotspot antes da TV.

## Atualizar (N → N+1)

Não há OTA. Atualizar é instalar o APK novo por cima, pelo pendrive, **sem
desinstalar**, assinado com **a mesma chave** e `versionCode` maior.
Credencial, config, cache, fila de comprovantes e segmentos operacionais
sobrevivem. O teste N → N+1 está em `docs/release-producao.md` e no
checklist (itens 34–38); fazer em bancada antes de cada versão nova. O
N+1 de teste sai de `scripts/release-teste-n-mais-1.sh` (versionCode só na
linha de comando, mesma chave, "NAO-DISTRIBUIR" no nome).

## Reverter

O Android não instala `versionCode` menor por cima (downgrade). Reverter
uma versão com defeito é **publicar uma versão nova** (`versionCode` maior)
com o código anterior, assinada com a mesma chave. A alternativa —
desinstalar e instalar a antiga — apaga a credencial e os comprovantes não
enviados: só com a fila vazia (bloco técnico: "Comprovantes aguardando
envio: 0") e reprovisionando a tela.

**3.0.0 começa do zero** (decisão do dono, 05/10/2026): TVs de teste com
debug ou 2.0.0 são desinstaladas e reinstaladas; nada da instalação de teste
é migrado.

## Restaurar

Não há backup (`dataExtractionRules` exclui tudo de backup e de
transferência entre aparelhos — a credencial é da TV, não da conta): o
estado que importa é a fila de proof-of-play e os segmentos operacionais, e
o servidor guarda a cópia de verdade depois da confirmação. TV que perde o
armazenamento (reset, troca) perde só o que estava pendente de envio.
Restaurar uma tela = instalar o APK e provisionar de novo com um código novo.

**Atualização de firmware da SEMP pede reset de fábrica** (procedimento
oficial). Antes: deixar a fila esvaziar (online, bloco técnico com 0
pendentes). Depois: refazer os ajustes da TV, reinstalar o APK e
provisionar com código novo.

## Responder a incidente

**App não sobe depois de ligar a TV**: `adb logcat | grep BootReceiver`. O app
trata `BOOT_COMPLETED` e `QUICKBOOT_POWERON`. Se nenhum chegar, o watchdog
não tem como agir (ele é rearmado pelo boot ou pela abertura manual) — é
limite do firmware. Em TV Android 10+: conferir "Exibir sobre outros apps"
(o bloco técnico diz "Retorno automático BLOQUEADO" quando falta). A TV
pode também ter voltado da tomada em standby (LED aceso): ligar pelo
controle; se o menu de fábrica tiver "Power on Mode", deixar em ON.

**TV voltou para a tela de instalação sozinha**: o servidor respondeu 401 —
Player revogado, tela arquivada ou código antigo. Gerar código novo no admin e
provisionar. A fila de proof-of-play foi mantida e será enviada.

**Cartão da marca em vez de anúncios**: fora do horário do ponto, ou tela em
reparo/inativa (403). Conferir no admin.

**"Não foi possível carregar a programação"**: sem playlist do servidor e sem
cache, ou playlist vazia. Conferir internet do ponto; o app tenta de novo a
cada 60 s.

**Fila de proof-of-play crescendo** (heartbeat mostra `fila.pendentes` alto):
rede do ponto ou 403. Comprovante só sai da TV com a resposta do servidor
(não expira por idade); o servidor responde `janela_expirada` quando for
tarde, pelo relógio dele. O teto é 150.000 linhas (~8,7 dias de tela 24 h
com itens de 5 s); estourar vai para o diário como `FILA_CHEIA`.

**"Sem hora do servidor neste boot"** no bloco técnico: a TV religou sem
rede. O tempo operacional desse boot só vai ao servidor se ela falar com
ele antes do próximo reboot; senão é descartado e contado no diário
(`OPERACAO_SEM_ANCORA`) — nunca vai com o relógio da TV.

**Imagem de ponta-cabeça**: build nova com `ROTACAO_GRAUS = 270` em
`Produto.kt`. Nunca tornar configurável.

## Chave de assinatura

**Passo manual obrigatório antes da primeira instalação definitiva.**

Toda versão futura do player precisa ser assinada com **a mesma chave** do
APK já instalado. O Android recusa a troca de assinatura: um APK assinado com
outra chave não atualiza, só instala depois de uma desinstalação — e
desinstalar apaga a credencial da tela e a fila de proof-of-play inteira.

Ou seja: TVs que saírem com o APK de debug, ou com uma chave que se perca
depois, só trocam de versão com desinstalação + reprovisionamento, uma a uma.

**A chave nasce uma vez, num ambiente persistente sob controle do dono,
fora do repositório.** Sessão efêmera (contêiner descartável) nunca gera a
chave definitiva — não haveria como recuperá-la. Passo a passo completo,
com o registro da impressão digital: `docs/release-producao.md`, "Primeira
assinatura".

### Gerar (o dono, na própria máquina)

```sh
keytool -genkeypair -v \
  -keystore mostrai-release.jks \
  -alias mostrai \
  -keyalg RSA -keysize 4096 -validity 10000
```

Validade longa de propósito: uma chave que expira é uma frota que para de
atualizar.

### Configurar o build

`keystore.properties` na raiz do repositório (já está no `.gitignore`):

```properties
storeFile=/caminho/absoluto/para/mostrai-release.jks
storePassword=...
keyAlias=mostrai
keyPassword=...
```

Alternativa sem arquivo (CI, máquina compartilhada): as mesmas quatro
chaves em variáveis de ambiente/segredos do executor, gravadas em
`keystore.properties` só durante o build e apagadas depois — nunca no
repositório nem no log.

Sem esse arquivo o build de release sai **sem assinatura**
(`app-release-unsigned.apk`), de propósito — falhar aqui custa um minuto;
descobrir em campo custa uma visita por tela. APK não assinado com a chave
definitiva **não** é produção.

### Guardar

- O `.jks` **nunca** entra no Git.
- Guardar em pelo menos dois lugares, um deles offline.
- Guardar as senhas separadas do arquivo.
- Perder a chave é irreversível: não há recuperação, e a frota inteira fica
  sem caminho de atualização.

### Conferir qual chave assinou um APK

```sh
apksigner verify --print-certs app-release.apk
```

## O que só o dono faz

Ver `docs/pendencias.md`, seção "Só o dono faz".
