# Checklist físico — Mostraí Player 3.0.0 (V1 de produção) na SEMP TCL 32S6500S

> 05/10/2026. Substitui o checklist da 2.0.0. Cada item vira PASS/FAIL com
> o que se viu; FAIL anota hora, foto/vídeo e o bloco técnico (VOLTAR).

**APK:** `Mostrai-Player-3.0.0-release.apk`, assinado com a chave
definitiva, gerado por `scripts/release-candidato.sh`. Antes de começar,
anotar o `sha256` e a impressão digital do certificado do `REGISTRO.txt`.
APK `…-NAO-ASSINADO.apk` ou `app-debug.apk` **não** serve para este
checklist.

**Duas TVs:**

- **TV A — operação normal:** rede estável, uso como em loja.
- **TV B — tortura offline:** cortes de rede, tomada, relógio, disco.

**Antes, no admin:** PIN de saída definido; uma tela de teste por TV com
ao menos um criativo `contabiliza: true` na hora corrente e o institucional
da Mostraí; horário do ponto aberto agora. Para a TV B, uma tela de **ponto
móvel** (tempo operacional).

**Antes, em cada TV:** desinstalar qualquer Mostraí Player anterior
(produção começa do zero); suspensão automática desligada; modo loja e
protetor de tela desligados.

## TV A — instalação e operação normal

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 1 | Instalar o APK 3.0.0 pelo pendrive | Instala; **não** aparece "instalação anormal" | |
| 2 | Abrir pelo ícone | Sem aviso de "app feito para versão antiga"; vídeo de abertura uma vez; tela de instalação | |
| 3 | Olhar a tela com a TV montada | Texto na orientação certa (90°); se de ponta-cabeça, ver "Rotação" abaixo | |
| 4 | ID (`235` → `M-0235`) + código `XXXX-XXXX` → CONECTAR | Some a tela; código não aparece em lugar nenhum; admin mostra instalada | |
| 5 | Aguardar ~15 s | Admin: config aplicada (mesma `configVersion`), heartbeat a cada 15 s | |
| 6 | Aguardar a playlist | Toca os itens da hora; nada de "Atualizando conteúdo…" parado | |
| 7 | Item institucional | Toca pelo tempo do item; não gera comprovante | |
| 8 | Anúncio `contabiliza: true` termina | Comprovante `contabilizado` no admin em ≤ 60 s, com início/fim na hora certa (relógio do servidor) | |
| 9 | Margens (sup/dir/inf/esq = 5, uma por vez) | Cada lado visual encolhe em ≤ 20 s, sem reiniciar o vídeo | |
| 10 | VOLTAR | Pede PIN; vídeo continua por trás; bloco técnico mostra versão `3.0.0+4`, aparelho, `Android 8…(API 26)`, estado `PLAYING`, espaço livre, 0 comprovantes pendentes. **Nenhuma** chave, token, código ou URL | |
| 11 | VOLTAR de novo | Fecha o pedido de PIN | |
| 12 | VOLTAR → PIN errado 3× | "PIN incorreto", depois "Aguarde N s"; vídeo segue | |
| 13 | HOME | Vai ao launcher; Player volta sozinho em ~5–10 s (tentativas em 10, 20, 40, 60 s) | |
| 14 | VOLTAR → PIN certo | App fecha; esperar 10 min: **não** reabre | |
| 15 | Abrir pelo ícone | Volta sem pedir ID/código | |
| 16 | Reiniciar a TV pelo menu | Abre sozinho e toca **no item da hora** | |
| 17 | Tirar da tomada 1 min e religar | Liga (ou fica em standby — anotar qual); abre sozinho ao ligar | |
| 18 | Admin: revogar o Player | Em ≤ 15 s volta para a instalação | |
| 19 | Provisionar de novo (mesmo ID + código novo) | Volta a tocar; comprovantes pendentes são enviados | |
| 20 | Admin: tela em reparo (403) | Cartão da marca; nada de anúncio; ao reativar, volta | |
| 21 | Fora do horário do ponto | Cartão/estado "fora do horário"; volta sozinho no horário | |

## TV B — tortura offline (ponto móvel)

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 22 | Instalar e provisionar como na TV A (itens 1–6) | Idem | |
| 23 | Com rede, deixar a playlist da hora baixar inteira; VOLTAR | "Mídia: N/N no aparelho"; "PRONTO PARA OFFLINE ATÉ <fim da janela>" | |
| 24 | Tirar a rede | Vídeo não para; nada de "sem internet" para o público | |
| 25 | Offline atravessando a virada de hora | Até o fim da janela: programação. Depois: **só** institucional (estado `IDLE` no bloco técnico), nenhum anúncio repetido, sem tela preta | |
| 26 | Admin durante o offline | "Sem comunicação" (não "desligada") depois de 2 min | |
| 27 | Offline: tirar da tomada e religar | Volta ao institucional (ou à programação ainda válida) sem pedir nada; bloco técnico: "sem hora do servidor neste boot" | |
| 28 | Offline ≥ 3 h (ideal: uma noite, ≥ 9 h, para a suspensão de 4/6/8 h) | Nada de tela preta, nada de anúncio vencido; TV **não** entrou em suspensão | |
| 29 | Religar a rede | Em ≤ 1 min: playlist nova; comprovantes do período offline no admin; bloco técnico com 0 pendentes | |
| 30 | Ficha da tela (ponto móvel) | Tempo operacional do período offline aparece (segmentos), **sem** o tempo do boot que nunca teve rede (item 27, se não reconectou antes de outro reboot) | |
| 31 | Offline, mudar a hora da TV 2 dias para frente (Configurações › Data e hora, manual) | Nenhum anúncio toca fora da janela; ao voltar a rede, comprovantes chegam com a hora certa | |
| 32 | Offline, mudar a hora da TV 1 ano para trás; religar a rede | Comprovantes pendentes **são enviados** (não ficam presos) | |
| 33 | Pendrive cheio de arquivos plugado / disco quase cheio (se possível) | Bloco técnico mostra espaço; download para antes da reserva; vídeo segue | |

## Atualização N → N+1 com a mesma chave (obrigatório antes do primeiro cliente)

Procedimento completo em `docs/release-producao.md`.

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 34 | Com a TV A tocando e algum comprovante pendente (tirar a rede um minuto), instalar o N+1 de teste (`versionCode 5`, mesma chave) por cima, **sem desinstalar** | Instala | |
| 35 | Abrir | Toca sem pedir ID/código; bloco técnico `3.0.1-teste+5`; comprovantes pendentes enviados ao voltar a rede | |
| 36 | Instalar o 3.0.0 (`versionCode 4`) por cima | Android **recusa** (downgrade) | |
| 37 | Instalar um APK com outra assinatura (debug) por cima | Android **recusa** | |
| 38 | Desinstalar, reinstalar o 3.0.0 oficial do zero e provisionar | TV pronta para o provisionamento oficial | |

## Vídeo em pé 1080×1920

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 39 | Criativo H.264 1080×1920 30 fps na playlist | Toca em pé, sem tarja, sem travar; anotar se falhou e a mensagem do bloco técnico | |
| 40 | Se 39 falhar: o mesmo com 608×1080 (altura ≤ 1088) | Toca — vira regra de produto para criativos | |

## Soak (resistência)

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 41 | TV A: 72 h contínuas com rede | Sem tela preta, sem travar; comprovantes contínuos no admin; anotar memória/temperatura se possível | |
| 42 | TV B: 24 h alternando 1 h offline / 1 h online | Nenhum comprovante perdido (contar no admin vs. exibições esperadas); segmentos operacionais contínuos | |

## Android 10+ (só se houver TV assim)

Antes: `adb shell appops get br.com.mostrai.player SYSTEM_ALERT_WINDOW` e
`adb shell getprop ro.config.low_ram`. TV "low RAM" (comum em Google TV
barata) pode não deixar conceder a permissão — aí o retorno automático não
existe nesse modelo; registrar.

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 43 | Instalar, **sem** conceder "Exibir sobre outros apps"; HOME | Player **não** volta; bloco técnico: "Retorno automático BLOQUEADO" | |
| 44 | Conceder "Exibir sobre outros apps"; HOME | Volta em ~5–10 s; religar a TV abre o Player | |
| 45 | Android 16: VOLTAR | Pede o PIN (voltar preditivo tratado) | |

## Rotação (item 3)

Se a imagem estiver invertida, registrar **somente**
`ROTATION_PHYSICAL_CORRECTION_REQUIRED = 270`. A correção é uma build nova
com `Produto.ROTACAO_GRAUS = 270`. Nunca rotação configurável.

## Se o item 1 falhar

Não corrigir às cegas: comparar o manifesto com o último APK que instalou
e bisseccionar, como em
`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`.
O 3.0.0 mudou o manifesto (+`SYSTEM_ALERT_WINDOW`, `networkSecurityConfig`,
`dataExtractionRules`, `targetSdk 36`) — candidatos naturais da bissecção.
