# Watchdog levava minutos para trazer o Player de volta do HOME

**Data:** 02/10/2026 · **Achado em:** revisão pedida pelo dono ("retornando
o mais rápido possível sem transpassar o botão HOME") · **Severidade:**
média (tela comercial no launcher por 5–7 min)

## O que acontecia

O `HOME` não é interceptável por app nenhum, e declarar o Player como
launcher faz o instalador da TCL recusar o APK
(`2026-09-25-instalador-tcl-recusava-app-com-category-home.md`). O único
caminho de volta era o alarme do watchdog: inexato, a cada 2 min, e só
reabria depois de 5 min sem sinal de vida. Na prática, 5 a 7 minutos de
launcher na frente do público. Crash do processo tinha a mesma espera.

## Correção

- `PlayerActivity.onStop` (sem ser troca de configuração) chama
  `Watchdog.saiuDaFrente`: alarme **exato** (`setExact`) de retorno em 5 s,
  repetido em 10, 20, 40 e 60 s enquanto o Player não voltar. 5 s é o
  atraso que o próprio Android impõe a abrir atividade depois do HOME.
- `onStart` → `Watchdog.naFrente` cancela o retorno pendente.
- TV em standby (`PowerManager.isInteractive` falso) não é acordada.
- Saída por PIN cancela os dois alarmes; sem instalação, não agenda.
- O alarme de segurança (crash) passou de 2 min/5 min para 60 s/90 s, com
  sinal de vida a cada 30 s.

Testes: `WatchdogRetornoTest`, `WatchdogInstalacaoTest`.

## Para não repetir

Medir o pior caso em segundos diante do público, não o intervalo do alarme.
Alarme inexato no Android 8 pode atrasar até 75 % do intervalo.
