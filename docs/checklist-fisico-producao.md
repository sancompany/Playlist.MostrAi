# Checklist físico — Mostraí Player 3.0.1 na SEMP TCL 32S6500S

> 05/10/2026, ampliado em 06/10/2026 para a 3.0.1 (atualização oficial por
> pendrive). Cada item vira PASS/FAIL com o que se viu; FAIL anota hora,
> foto/vídeo e o bloco técnico (VOLTAR).

**APKs** (todos assinados com a chave definitiva; anotar `sha256` e
impressão digital do `REGISTRO.txt` de cada um):

- `Mostrai-Player-3.0.0-release.apk` (`versionCode 4`) — a **base**: TV A
  começa nele (itens 1–21) para provar a atualização 3.0.0 → 3.0.1;
- `Mostrai-Player-3.0.1-release.apk` (`versionCode 6`) — o **candidato**:
  TV B instala direto; TV A recebe por cima (itens 34–37);
- `Mostrai-USB-3.0.1-teste-n7-NAO-DISTRIBUIR.zip` (`versionCode 7`) — só
  para provar o modal do pendrive (itens 46–61); **nunca** a campo.

APK `…-NAO-ASSINADO.apk` ou `app-debug.apk` **não** serve para este
checklist.

**Duas TVs:**

- **TV A — operação normal:** rede estável, uso como em loja.
- **TV B — resiliência:** quedas **temporárias** de rede, tomada, relógio,
  disco. A Mostraí é *online-first*: operação offline prolongada não é
  cenário suportado (ponto sem internet local usa dados móveis).

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
| 1 | Instalar o APK 3.0.0 pelo pendrive (gerenciador de arquivos da TV) | Instala; **não** aparece "instalação anormal" | |
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

## TV B — resiliência a quedas temporárias

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 22 | Instalar o **3.0.1** do zero e provisionar como na TV A (itens 1–6) | Idem; **não** aparece "instalação anormal" (o 3.0.1 declara permissões novas — ver "Se o item 1 ou 22 falhar") | |
| 23 | Com rede, deixar a playlist da hora baixar inteira; VOLTAR | "Mídia: N/N no aparelho"; "PRONTO PARA OFFLINE ATÉ <fim da janela>" | |
| 24 | Tirar a rede | Vídeo não para; nada de "sem internet" para o público | |
| 25 | Offline atravessando a virada de hora | Até o fim da janela: programação. Depois: **só** institucional (estado `IDLE` no bloco técnico), nenhum anúncio repetido, sem tela preta | |
| 26 | Admin durante o offline | "Sem comunicação" (não "desligada") depois de 2 min | |
| 27 | Offline: tirar da tomada e religar | Volta ao institucional (ou à programação ainda válida) sem pedir nada; bloco técnico: "sem hora do servidor neste boot" | |
| 28 | Offline ~1 h (queda temporária, não operação offline) | Nada de tela preta, nada de anúncio vencido | |
| 29 | Religar a rede | Em ≤ 1 min: playlist nova; comprovantes do período offline no admin; bloco técnico com 0 pendentes | |
| 30 | Ficha da tela (ponto móvel) | Tempo operacional do período offline aparece (segmentos), **sem** o tempo do boot que nunca teve rede (item 27, se não reconectou antes de outro reboot) | |
| 31 | Offline, mudar a hora da TV 2 dias para frente (Configurações › Data e hora, manual) | Nenhum anúncio toca fora da janela; ao voltar a rede, comprovantes chegam com a hora certa | |
| 32 | Offline, mudar a hora da TV 1 ano para trás; religar a rede | Comprovantes pendentes **são enviados** (não ficam presos) | |
| 33 | Pendrive cheio de arquivos plugado / disco quase cheio (se possível) | Bloco técnico mostra espaço; download para antes da reserva; vídeo segue | |

## Atualização 3.0.0 → 3.0.1 com a mesma chave (obrigatório antes do primeiro cliente)

O 3.0.0 não tem atualizador: esta é a última atualização feita à mão.
Prova que a frota recebe a versão seguinte sem desinstalar. Procedimento em
`docs/release-producao.md`.

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 34 | TV A no 3.0.0 tocando, com comprovante pendente (tirar a rede um minuto). Abrir `MOSTRAI/update/Mostrai-Player.apk` do pacote 3.0.1 pelo gerenciador de arquivos e instalar **por cima, sem desinstalar** | Instala, sem "app não instalado" nem "instalação anormal" | |
| 35 | Esperar / abrir | O Player volta sozinho (ou abre pelo ícone) e toca **sem pedir ID/código**; bloco técnico `3.0.1+6` e "Última atualização: 3.0.1 (6) …"; comprovantes pendentes enviados ao voltar a rede | |
| 36 | Instalar o 3.0.0 (`versionCode 4`) por cima | Android **recusa** (downgrade) | |
| 37 | Instalar um APK com outra assinatura (debug) por cima | Android **recusa** | |
| 38 | Item retirado: o 3.0.1 oficial é o próprio N+1 (o `versionCode 5` de teste foi queimado e não se usa) | — | |

## Vídeo em pé 1080×1920

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 39 | Criativo H.264 1080×1920 30 fps na playlist | Toca em pé, sem tarja, sem travar; anotar se falhou e a mensagem do bloco técnico | |
| 40 | Se 39 falhar: o mesmo com 608×1080 (altura ≤ 1088) | Toca — vira regra de produto para criativos | |

## Soak (resistência) — em etapas, não bloqueia o primeiro piloto

Primeiro 2–4 h; estável, 24 h; depois 48–72 h como validação adicional.
Crash, ANR ou tela preta em qualquer etapa: parar e investigar. O APK está
pronto para o primeiro ponto piloto depois da etapa de 2–4 h passar.

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 41 | TV A: 2–4 h contínuas com rede; depois 24 h (cobre a suspensão automática de 4/6/8 h); depois 48–72 h | Sem tela preta, sem travar, TV **não** entrou em suspensão; comprovantes contínuos no admin; anotar temperatura se possível | |
| 42 | TV B: 4–6 h alternando 30 min sem rede / 30 min com rede | Nenhum comprovante perdido (contar no admin vs. exibições esperadas); segmentos operacionais contínuos | |

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

## Atualização por pendrive (3.0.1)

Na TV A já no 3.0.1 (itens 34–35). Procedimento e regras em
`docs/atualizacao-usb.md`. Pendrive FAT32 ou exFAT. Para o modal aparecer é
preciso uma versão **acima** da instalada: o pacote de teste `versionCode 7`
(`Mostrai-USB-3.0.1-teste-n7-NAO-DISTRIBUIR.zip`, pasta `MOSTRAI` na raiz do
pendrive). Para os itens de recusa, outros pendrives:

- **P-comum:** fotos/vídeos, sem pasta `MOSTRAI`;
- **P-teste:** o pacote de teste `versionCode 7`;
- **P-igual:** o pacote oficial 3.0.1 (`Mostrai-USB-3.0.1.zip`);
- **P-antigo:** um `MOSTRAI/update/Mostrai-Player.apk` com o 3.0.0;
- **P-outra-chave:** um `MOSTRAI/update/Mostrai-Player.apk` com o `app-debug.apk` (sem `update.json`).

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 46 | Com o vídeo tocando, conectar **P-comum** | Nada aparece para o público; vídeo **não** para. Primeira vez: pode aparecer "Pendrive conectado. Para procurar atualização do Mostraí, permita o acesso ao pendrive." — **Agora não**. Bloco técnico: "USB conectado: sim" e o resultado | |
| 47 | Retirar P-comum; conectar **P-teste** | Primeira vez: modal de acesso ao pendrive → **Permitir acesso** → diálogo do Android → Permitir. Anotar qual apareceu (permissão de armazenamento ou seletor de pastas) | |
| 48 | (sequência do 47) | Em segundos: **ATUALIZAÇÃO MOSTRAÍ — Nova versão encontrada — Atual: 3.0.1 / Nova: 3.0.1-teste-n7 — [Atualizar agora] [Depois]**; vídeo segue por baixo. Se o bloco técnico disser "este Android não deixa o Mostraí ler o pendrive": **FAIL do caminho USB nesta TV** — anotar e pular para o 58 | |
| 49 | **Depois** | Modal some; vídeo segue; esperar 5 min com o pendrive: **não** reaparece | |
| 50 | Retirar e reconectar P-teste | Modal reaparece | |
| 51 | VOLTAR com o modal | Equivale a Depois (não pede o PIN) | |
| 52 | VOLTAR → bloco técnico | Seção ATUALIZAÇÃO: versão instalada 3.0.1, permissão para instalar, USB conectado, versão encontrada `3.0.1-teste-n7 (7)`, última verificação, último resultado; botões **Verificar USB** e **Instalar atualização**. Nenhum caminho, hash ou certificado | |
| 53 | **Instalar atualização** (ou reconectar) → **Atualizar agora**, primeira vez | Modal "Para atualizar o Mostraí pelo pendrive, permita instalações pelo Mostraí Player." → **Permitir atualizações** abre a tela do Android; anotar o caminho na TCL (Fontes desconhecidas / Instalar apps desconhecidos) → liberar o Mostraí Player → VOLTAR | |
| 54 | Ao voltar | Modal "Nova versão encontrada" de novo; durante a tela do Android o Player **não** pulou por cima (watchdog em pausa) | |
| 55 | **Atualizar agora** → no instalador do Android, **Cancelar** | Volta ao Player no 3.0.1, tocando; bloco técnico "Instalação não concluída…" | |
| 56 | Retirar o pendrive; HOME | O Player volta em ~5–10 s (a pausa acabou) | |
| 57 | Reconectar P-teste → **Atualizar agora** → **Instalar** | Instala; o Player volta **sozinho** em `3.0.1-teste-n7+7`, mesmo ID, sem pedir código; comprovantes pendentes enviados; "Última atualização" no bloco técnico | |
| 58 | Conectar **P-igual** (TV no 7: vira "versão anterior") e **P-antigo** | Nenhum modal; bloco técnico "Versão do USB anterior à instalada." | |
| 59 | Conectar **P-outra-chave** | Nenhum modal; "APK do Mostraí com outra assinatura: recusado." | |
| 60 | P-teste conectado com a TV **desligada da tomada**; ligar | O Player abre e oferece (no 7: "Esta versão já está instalada." — repetir o item com a TV ainda no 3.0.1, se possível) | |
| 61 | Fim do teste: a TV está no `versionCode 7` (teste) | **Desinstalar**, instalar o 3.0.1 oficial e provisionar de novo antes de qualquer uso real. A próxima versão oficial será `versionCode 8` ou mais | |

Não testado de propósito: retirar o pendrive durante a cópia (o APK de
~8 MB copia em ~1 s; coberto por teste automático).

## Ligar a tela (teste de capacidade — não bloqueia o piloto)

Só registra se a TCL deixa um app acender o painel. TV **sem energia**
(tomada) nunca liga por app; o teste é com **standby** (botão do controle).

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 62 | VOLTAR → **Testar ligar tela (2 min)** | Bloco técnico "Ligar tela: teste agendado" | |
| 63 | Em até 2 min, pôr a TV em standby pelo controle; esperar 3 min **sem tocar em nada** | Anotar: o painel acendeu sozinho e o Player apareceu? (foto/vídeo) | |
| 64 | Ligar a TV (se não acendeu) → VOLTAR | "Ligar tela: …" diz se o alarme veio na hora ou atrasado (aparelho dormia), como a tela estava e quanto tempo depois acendeu. "(pela pessoa?)" = **não** acendeu sozinho | |
| 65 | Repetir com "Ligar instantâneo"/"Inicialização rápida" ligado e desligado, se a TCL tiver | Anotar cada combinação | |

Resultado PASS só se o painel acendeu sem ninguém tocar. Mesmo PASS, o
Player continua sem acender a tela sozinho até decisão nova
(`docs/permissoes-especiais.md`, "Ligar a tela").

## Rotação (item 3)

Se a imagem estiver invertida, registrar **somente**
`ROTATION_PHYSICAL_CORRECTION_REQUIRED = 270`. A correção é uma build nova
com `Produto.ROTACAO_GRAUS = 270`. Nunca rotação configurável.

## Se o item 1 ou 22 falhar

Não corrigir às cegas: comparar o manifesto com o último APK que instalou
e bisseccionar, como em
`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`.
O 3.0.0 mudou o manifesto (+`SYSTEM_ALERT_WINDOW`, `networkSecurityConfig`,
`dataExtractionRules`, `targetSdk 36`); o 3.0.1, de novo
(+`REQUEST_INSTALL_PACKAGES`, `READ_EXTERNAL_STORAGE` até o 29,
`WAKE_LOCK`, `requestLegacyExternalStorage`, `<queries>`, `FileProvider`,
receptor de `MY_PACKAGE_REPLACED`) — candidatos naturais da bissecção.
