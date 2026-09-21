# RUNBOOK — Mostraí Player

Como operar, reverter e restaurar o app instalado numa TV. Proporcional ao
projeto (Lei 0): sem serviço hospedado, sem banco remoto — quase tudo aqui é
local ao aparelho.

## Operar

**Ver o estado do aparelho** (painel de manutenção): 3 acionamentos do botão
OK/CENTER do controle remoto em até 3 segundos, digitar o PIN (4 dígitos).
Mostra: tela e chave configuradas, modo de contrato do servidor (novo ou
degradado), origem da última playlist (servidor/cache/institucional), erro
do aparelho mais recente, e o estado da fila de proof-of-play (pendentes e
perdas). `VOLTAR` sai do painel sem afetar a reprodução.

**Ver logs em bancada** (aparelho conectado por USB ou ADB via rede):

```sh
adb logcat --pid=$(adb shell pidof -s br.com.mostrai.player)
```

Tags relevantes: `MostraiPlayer` (ciclo de reprodução), `MostraiApi` (rede),
`FilaProofOfPlay` (fila de comprovante), `PlaylistRepositorio`.

**Forçar uma nova busca de playlist**: reiniciar o app (o `onStart` sempre
busca com reposicionamento). Não há comando remoto para isso na v1.

## Reverter

Não há atualização remota (OTA — item em aberto, seção "Em aberto" do
`README.md`). Reverter é reinstalar uma versão anterior do APK por sideload:

```sh
adb install -r app-debug-<versao-anterior>.apk
```

A configuração do aparelho (`ConfigAparelho`, `SharedPreferences`) e a fila
de proof-of-play (`ProofOfPlayDb`, SQLite) **sobrevivem** a uma reinstalação
com `-r` (não usar `adb uninstall`, que apaga os dois). Se precisar mesmo
apagar o estado local, `adb uninstall br.com.mostrai.player` — isso descarta
qualquer proof-of-play ainda não enviado, sem contá-lo como perda (não passa
pelo contador de `FilaProofOfPlay`, porque o processo nem chega a rodar).

## Restaurar

Não há backup a restaurar: a fila de proof-of-play é o único estado que
importa manter, e ela é local ao aparelho — não tem cópia remota por
desenho (decisão: o servidor é quem tem a cópia de verdade, uma vez que o
evento foi `contabilizado`). Um aparelho que perde o armazenamento (troca de
TV, reset de fábrica) perde o que estava pendente de envio; o que já foi
enviado com sucesso já está no servidor.

**Teste de restauração aplicável aqui**: reinstalar o APK numa TV limpa e
confirmar que o app reprovisiona (ver `README.md`, "Instalar e provisionar
em bancada") e volta a tocar — não há estado de servidor a restaurar deste
lado.

## Responder a incidente

**App não sobe depois de ligar a TV**: verificar se `BOOT_COMPLETED` chegou
(`adb logcat | grep BootReceiver`); alguns aparelhos usam
`QUICKBOOT_POWERON` em vez de `BOOT_COMPLETED` — o app trata os dois
(`BootReceiver.kt`). Se nenhum dos dois disparar, é limitação do firmware do
aparelho, fora do controle deste app.

**Tela mostra "aparelho ainda não provisionado"**: falta `dispositivoId`,
`chaveAparelho` ou `baseUrl` em `ConfigAparelho`. Reprovisionar via `adb`
(`README.md`) até o provisionamento de campo (item em aberto) existir.

**Painel mostra "servidor em contrato antigo"**: o backend ainda não expôs
o envelope novo (`versaoContrato`) — não é falha do app, é o estado esperado
enquanto a outra sessão (repo `sancompany/mostrai`) não publica o contrato
da seção 6.

**Fila de proof-of-play crescendo sem enviar** (painel mostra "pendente"
alto e sem queda): checar `X-Aparelho-Id` (chave pode ter sido revogada no
admin → erro 401/403, fila fica intacta e visível no painel) e conectividade
de rede do comércio.

## O que só o dono faz

Ver `docs/pendencias.md`, seção "Só o dono faz".
