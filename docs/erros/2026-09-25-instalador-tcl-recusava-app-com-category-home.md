# Instalador da TV TCL recusava o app que se declarava launcher

**Marcar como de ecossistema** — o padrão (declarar no manifesto uma
capacidade de sistema, como ser launcher, que o Android puro aceita mas o
instalador do fabricante recusa) não é específico deste projeto: qualquer
APK para TV de fabricante com instalador próprio corre o mesmo risco, e ele
só aparece na instalação, no aparelho real.

## O que aconteceu

O APK 1.0.0 (versionCode 2), primeiro com o lote V2, não instalava na SEMP
TCL 32S6500S (Android TV 8.0). A TV mostrava só **"instalação anormal"**,
sem código de erro. O 0.1.0 de 21/09 tinha instalado na mesma TV.

O lote V2 tinha acrescentado à `PlayerActivity` um segundo
`intent-filter` (`MAIN` + `HOME` + `DEFAULT`), para o player poder ser
escolhido como launcher padrão — o quiosque possível sem Device Owner.

## Como a causa foi provada

1. **Conflito de assinatura descartado.** O app antigo já tinha sido
   desinstalado antes da tentativa; o certificado do 0.1.0 e do 1.0.0 era o
   mesmo, de qualquer forma.
2. **Pacote íntegro.** Assinatura v2 válida (`apksigner verify`), ZIP sem
   erro, mesmo esquema de assinatura, mesmo formato de dex (`038`), sem
   bibliotecas nativas, mesma toolchain (AGP 8.7.3) do 0.1.0.
3. **Diferença entre o que instalava e o que não instalava**, manifesto a
   manifesto (`aapt2 dump xmltree`): só o filtro `HOME`, a permissão
   `REQUEST_INSTALL_PACKAGES` (OTA) e dois receivers não exportados sem
   intent-filter.
4. **Bissecção na TV.** Dois APKs de diagnóstico gerados do mesmo código,
   com a mesma assinatura: A = sem o filtro `HOME`; B = sem `HOME` e sem
   `REQUEST_INSTALL_PACKAGES`. **O A instalou direto.** Como o A só difere
   do APK recusado pelo filtro `HOME`, a causa é o filtro — e a permissão
   de OTA não tem culpa (o A a mantém).

O porquê exato de o instalador da TCL recusar não é visível sem `logcat`
(a mensagem genérica esconde o código). O fato é o que importa aqui: com o
filtro, não instala; sem ele, instala.

## A regra que isso gerou

Tudo que muda o **manifesto** — sobretudo categorias e permissões de
sistema — precisa de uma instalação no aparelho real antes de ser dado como
pronto. Teste unitário e Robolectric não passam pelo instalador do
fabricante. E "instalação anormal" na TCL não diz a causa: o caminho mais
curto é comparar com o último APK que instalou e bisseccionar o manifesto.

## Onde foi corrigido

`AndroidManifest.xml`: o `intent-filter` de `HOME` saiu da
`PlayerActivity`, com comentário explicando por que a ausência é
intencional (senão alguém devolve). Nenhum código dependia dele:
`Kiosk` usa lock task com Device Owner, não atividade preferida de HOME.

O que se perde: o player não pode mais ser escolhido como launcher padrão,
então a tecla HOME do controle sai para o launcher da TV. Quem traz o
player de volta é o `Watchdog` (5 minutos sem sinal de vida + alarme a cada
2 minutos → volta em 5–7 minutos). Era o comportamento que já valia em
toda TV onde ninguém escolhesse o player como padrão.

Sem teste automatizado: é o instalador do fabricante, só existe na TV.
Item 4 de `docs/checklist-fisico-producao.md` atualizado para conferir o
retorno pelo watchdog em vez do launcher.
