# Checklist físico — Mostraí Player 2.0.0 (MVP) na SEMP TCL 32S6500S

APK: **debug** da 2.0.0 (versionCode 3). Serve para validar o funcionamento; não
é o APK de produção, que precisa da chave definitiva (`RUNBOOK.md`).

Antes de começar, no admin: PIN de saída definido (Rede), uma tela de teste
com ao menos um criativo `contabiliza: true` na hora corrente, e o horário do
ponto aberto agora. Marque PASS/FAIL e anote o que viu em cada FAIL.

| # | Passo | Esperado | PASS/FAIL |
|---|---|---|---|
| 1 | Desinstalar qualquer Mostraí Player anterior; instalar o APK 2.0.0 pelo pendrive | Instalação conclui | |
| 2 | Observar a mensagem do instalador | **Não** aparece "instalação anormal" | |
| 3 | Abrir o app pelo ícone | Vídeo de abertura toca uma vez e cai na tela de instalação | |
| 4 | Olhar a tela de instalação com a TV montada | Texto na orientação certa (rotação 90°) para quem olha; D-pad move o foco na direção apertada | |
| 5 | Campo **ID da tela**: digitar o número (ex.: `235` ou `0235`) | Mostra `M-` + os dígitos; ao conectar, vale como `M-0235` | |
| 6 | Campo **código de instalação**: digitar os 8 caracteres gerados no admin | Mostra `XXXX-XXXX`; só letras/números do alfabeto do código | |
| 7 | CONECTAR | Some a tela de instalação; o código não fica visível em lugar nenhum; admin mostra a tela como instalada | |
| 8 | Aguardar até ~15 s | Admin mostra a config aplicada (mesma `configVersion`) | |
| 9 | Aguardar a playlist | Sai de "Atualizando conteúdo…" e começa a tocar os itens da hora | |
| 10 | Item institucional (vídeo da rede, ou cartão se vier sem `url`) | Toca pelo tempo do item e segue; não gera comprovante | |
| 11 | Deixar rodar 30 min | Reprodução contínua, sem tela preta, sem travar, sem barra de sistema | |
| 12 | No admin, margem **superior** = 5 | Em ≤ 20 s a imagem desce no topo **visual** | |
| 13 | Margem **direita** = 5 | Encolhe do lado direito visual | |
| 14 | Margem **inferior** = 5 | Encolhe embaixo | |
| 15 | Margem **esquerda** = 5 | Encolhe do lado esquerdo visual | |
| 16 | Cronometrar os itens 12–15 | Cada alteração aparece em ≤ 20 s, sem reiniciar app nem vídeo | |
| 17 | Depois de um anúncio `contabiliza: true` terminar | Proof-of-play `contabilizado` no admin em até ~60 s | |
| 18 | Tirar o cabo de rede / desligar o Wi-Fi | Vídeo não para | |
| 19 | Deixar 30 min offline | Continua tocando pelo cache, inclusive depois da virada de hora | |
| 20 | Durante o offline | Admin mostra "Sem sinal"; nada perdido na TV | |
| 21 | Religar a rede | Heartbeat volta em ≤ 15 s; admin mostra "Operando" | |
| 22 | Logo após a volta | Playlist e config sincronizam (mudar uma margem durante o offline e ver aplicar) | |
| 23 | Após a volta | Os comprovantes do período offline aparecem no admin; `fila.pendentes` volta a 0 | |
| 24 | Reiniciar a TV pelo menu (ou tirar da tomada) | — | |
| 25 | Após ligar | App abre sozinho e volta a tocar sem pedir ID/código | |
| 26 | Apertar HOME no controle | Vai para o launcher da TV | |
| 27 | Não mexer em nada | Watchdog traz o player de volta em 5–7 min | |
| 28 | VOLTAR → digitar PIN **errado** 3× | "PIN incorreto"; depois bloqueio "Aguarde N s"; o vídeo segue por trás | |
| 29 | VOLTAR → PIN **correto** | App fecha | |
| 30 | Esperar 10 min | Watchdog **não** reabre o app | |
| 31 | Abrir o app manualmente pelo ícone | Volta a tocar sem pedir ID/código | |
| 32 | HOME e esperar 7 min | Watchdog rearmado: o player volta sozinho | |
| 33 | No admin, revogar o Player | — | |
| 34 | Em ≤ 15 s | TV volta para a tela de instalação | |
| 35 | Gerar um código novo no admin | Admin mostra o código novo | |
| 36 | Provisionar de novo (mesmo ID + código novo) | Volta a tocar; comprovantes que estavam na fila são enviados | |

## Se a rotação estiver invertida (item 4)

Registrar **somente**:

```
ROTATION_PHYSICAL_CORRECTION_REQUIRED = 270
```

A correção é uma build nova com `Produto.ROTACAO_GRAUS = 270`. Não recriar
rotação dinâmica.

## Se o item 2 falhar

Não corrigir às cegas. Comparar o manifesto com o último APK que instalou e
bisseccionar, como em
`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`.
