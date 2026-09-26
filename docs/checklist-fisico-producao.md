# Checklist físico de produção — Mostraí Player

Fechamento final para produção, 25/09/2026. Complementa (não substitui) o
checklist de 13 itens em `docs/auditoria-confiabilidade-2026-09-23.md`,
seção 8 — aqui os itens são reordenados e agrupados no formato PASS/FAIL
pedido para o fechamento, com o teste de proof-of-play ponta a ponta (item
10) e o de OTA real (item 14) como os dois obrigatórios antes de cliente
real.

Nenhum destes 15 itens é testável neste ambiente (sem hardware Android TV,
sem `/dev/kvm` para emulador — `docs/pendencias.md`). Todos dependem do
dono, num SEMP TCL 32S6500S ou equivalente.

Para cada item: o que fazer, o comportamento esperado, e o que **falhar**
aqui bloqueia (não é feedback estético).

| # | Teste | Comportamento esperado | PASS/FAIL |
|---|---|---|---|
| 1 | Instalar o release APK assinado (pendrive ou `adb install`) | Instala sem erro; ícone/banner reais aparecem no launcher da TV | ☐ |
| 2 | Confirmar a assinatura | `apksigner verify --print-certs app-release.apk` mostra o certificado do `mostrai-release.jks` gerado (RUNBOOK.md) | ☐ |
| 3 | Primeiro boot | Vídeo de abertura toca uma vez; ciclo normal começa depois (institucional se sem provisionamento, ou playlist se já vier configurado pelo pendrive/build embutido) | ☐ |
| 4 | Sair com HOME e esperar | A tecla HOME do controle leva ao launcher da TV; sem tocar em mais nada, o player volta sozinho em 5–7 min (`Watchdog`). O player **não** se oferece como launcher padrão — o instalador da TV recusava o APK com isso (`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`) | ☐ |
| 5 | Reboot | Desligar/religar a TV: o player reabre sozinho (`BootReceiver` + `Watchdog`), sem precisar de ninguém tocar no controle | ☐ |
| 6 | Queda de energia no meio de uma exibição | Tirar da tomada durante um anúncio e religar: o app volta, não trava, e a exibição interrompida **não vira comprovante** (nunca teve `terminadoEm`) nem fica órfã na fila para sempre | ☐ |
| 7 | Reprodução 1080×1920 | Vídeo sem esticar, cortar errado ou faixa preta indevida; rotação aplicada de acordo com a montagem física real desta TV (`rotacaoTela` configurado) | ☐ |
| 8 | Fluidez | Pelo menos 1h de reprodução contínua sem engasgo, travamento de quadro ou reinício sozinho do app | ☐ |
| 9 | Playlist | Item troca no tempo certo (duração configurada), sem repetir nem pular, e reposiciona corretamente numa virada de hora/janela | ☐ |
| 10 | **Proof-of-play ponta a ponta** (obrigatório) | Backend programa uma mídia → a TV recebe → toca → `playing` → `ended` → confirmação chega ao backend → backend marca confirmada → dashboard reflete a entrega. Precisa acontecer pelo menos uma vez de ponta a ponta antes de qualquer cliente real | ☐ |
| 11 | Queda de Wi-Fi | Desligar o Wi-Fi do roteador (não do app): a TV continua tocando do cache; uma exibição concluída offline gera confirmação pendente na fila local | ☐ |
| 12 | Retorno de Wi-Fi | Religar o Wi-Fi: as confirmações pendentes são enviadas, e o heartbeat volta a reportar a tela como operando | ☐ |
| 13 | Heartbeat | No dashboard do admin, confirmar que a tela só aparece como "Operando" quando o heartbeat realmente chegou — não antes, não por suposição | ☐ |
| 14 | **OTA real** (obrigatório) | Com uma versão N instalada e assinada, publicar N+1 no backend: diálogo de instalação aparece **entre** itens (nunca durante um anúncio); confirmar instala a nova versão; credencial, `dispositivoId` e fila de proof-of-play sobrevivem à atualização; o player volta ao ciclo normal sozinho | ☐ |
| 15 | Kiosk / Device Owner | VOLTAR não sai do app (já corrigido em código, confirmar no hardware); tentar `adb shell dpm set-device-owner` e registrar se funciona ou falha neste modelo (há relato de falha em TV TCL, `docs/proximas-versoes.md`) — funcionando ou não, o player já opera sem Device Owner | ☐ |

## Depois destes 15

Só depois de todos os itens obrigatórios (10 e 14 sempre; os demais sem
nenhum FAIL que bloqueie reprodução, proof-of-play, segurança, atualização,
boot ou recuperação) o veredito muda de
**"PLAYER TECNICAMENTE PRONTO — AGUARDANDO TESTE FÍSICO"** para
**"PLAYER PRONTO PARA PRODUÇÃO"**.

Um FAIL num item não-obrigatório (ex.: item 15, Device Owner) não bloqueia
— o player já foi desenhado para operar sem ele. Um FAIL nos itens 6, 10,
14 ou 11/12 (integridade do comprovante, ciclo de OTA, recuperação offline)
bloqueia até corrigir.
