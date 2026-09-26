# RUNBOOK — Mostraí Player

Como operar, reverter e restaurar o app instalado numa TV. Sem serviço
hospedado deste lado: quase tudo aqui é local ao aparelho. O protocolo com o
servidor é `sancompany/MostrAi` → `docs/player-mvp-contract.md`.

## Operar

**Instalar e provisionar**: `README.md`, "Instalar numa TV". ID da tela
(`M-0235`) + código de instalação (`XXXX-XXXX`, 30 min, uso único) gerado no
admin.

**Ver o estado de uma tela**: no admin (Aguardando instalação, Operando, Fora
do horário, Sem sinal, Erro do Player). O Player manda `estado`, `erro`,
`fila` e a config aplicada a cada 15 s. Não há painel de diagnóstico na TV.

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
`Watchdog`. A chave do aparelho nunca aparece nos logs.

## Reverter

Não há OTA. Reverter é reinstalar a versão anterior do APK por sideload,
**assinada com a mesma chave** (senão o Android só instala depois de
desinstalar, e desinstalar apaga a credencial e a fila de proof-of-play).
Com a mesma chave, credencial, config e fila sobrevivem à reinstalação.

A 2.0.0 lê a fila SQLite da 1.x sem perda (migração não destrutiva). A
credencial da 1.x também é lida; se a tela não existir mais no backend, o
primeiro 401 leva à tela de instalação.

## Restaurar

Não há backup: o único estado que importa é a fila de proof-of-play, e o
servidor é quem guarda a cópia de verdade depois do `contabilizado`. TV que
perde o armazenamento (reset, troca) perde só o que estava pendente de envio.
Restaurar uma tela = instalar o APK e provisionar de novo com um código novo.

## Responder a incidente

**App não sobe depois de ligar a TV**: `adb logcat | grep BootReceiver`. O app
trata `BOOT_COMPLETED` e `QUICKBOOT_POWERON`. Se nenhum chegar, o watchdog
não tem como agir (ele é rearmado pelo boot ou pela abertura manual) — é
limite do firmware.

**TV voltou para a tela de instalação sozinha**: o servidor respondeu 401 —
Player revogado, tela arquivada ou código antigo. Gerar código novo no admin e
provisionar. A fila de proof-of-play foi mantida e será enviada.

**Cartão da marca em vez de anúncios**: fora do horário do ponto, ou tela em
reparo/inativa (403). Conferir no admin.

**"Não foi possível carregar a programação"**: sem playlist do servidor e sem
cache, ou playlist vazia. Conferir internet do ponto; o app tenta de novo a
cada 60 s.

**Fila de proof-of-play crescendo** (heartbeat mostra `fila.pendentes` alto):
rede do ponto ou 403. Nada se perde antes de 7 dias.

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

**Quem gera a chave é o dono, fora desta sessão e fora do repositório.**
Nenhuma sessão automatizada gera o keystore definitivo.

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
