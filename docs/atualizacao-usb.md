# Atualização oficial por pendrive — Player 3.0.1

06/10/2026. Como uma TV com o Mostraí Player recebe a versão nova sem
desinstalar, sem `adb`, sem Device Owner e sem rede. **Não aprovado em
hardware** até o checklist físico (`docs/checklist-fisico-producao.md`,
seção "Atualização por pendrive") passar na TCL.

## O que o técnico faz

1. Copia a pasta `MOSTRAI` do pacote (`Mostrai-USB-<versão>.zip`) para a
   raiz de um pendrive.
2. Conecta o pendrive na TV com o Player tocando (ou antes de ligar).
3. Aparece **ATUALIZAÇÃO MOSTRAÍ — Nova versão encontrada — Atual: 3.0.1 /
   Nova: 3.0.2 — [Atualizar agora] [Depois]**. O vídeo continua por baixo.
4. Atualizar agora → o instalador do Android pede a confirmação → Instalar.
5. O Player volta sozinho na versão nova, com o mesmo ID, a mesma
   credencial e as filas de comprovante e de tempo operacional intactas.

Primeira vez numa TV, o Android pode pedir antes: **acesso ao pendrive**
(Android 8–10: permissão de armazenamento; 11+: escolher a pasta do
pendrive no seletor) e **instalar apps pelo Mostraí Player**. Cada uma é
concedida uma vez.

## Pacote

```
MOSTRAI/
└── update/
    ├── Mostrai-Player.apk     (release assinado com a chave definitiva)
    └── update.json            {versionName, versionCode, sha256, certificateSha256}
```

Gerado só por `scripts/preparar-usb-update.sh <apk>`, que recusa APK fora
da chave definitiva, APK de teste sem `--teste`, e confere o APK com
`scripts/verificar-apk.sh --release`. O `update.json` não é fonte de
confiança: só pega erro de empacotamento (APK trocado ou corrompido).

Reserva: `Mostrai-Player.apk` na **raiz** do pendrive, sem `update.json`.
Nenhum outro lugar é procurado — nada de busca recursiva num pendrive
cheio de fotos.

## Quando o Player olha o pendrive

| Situação | Como percebe |
|---|---|
| Conectado com o Player na frente | aviso do Android (`MEDIA_MOUNTED`), na hora |
| Já conectado quando o Player abre (boot, volta do standby, volta do PIN) | levantamento no `onStart` |
| Firmware que não avisa | vigia de 30 s — só **lista** volumes (`StorageManager`); só lê o pendrive que apareceu |

Cada pendrive é lido **uma vez** por conexão. Fora da frente o Player não
lê nada. O pendrive é lido pela API pública (`StorageManager`,
`StorageVolume`), nunca listando `/storage` às cegas.

## Acesso ao pendrive — sem "todos os arquivos"

| Android | Caminho | O que o técnico faz |
|---|---|---|
| 8, 9, 10 (a TCL é 8.0) | leitura direta do volume (`/storage/<UUID>`) com `READ_EXTERNAL_STORAGE` | "Permitir acesso" → diálogo do Android → Permitir (uma vez) |
| 8–10 com firmware que esconde o USB dos apps | seletor de pastas (Storage Access Framework) | "Permitir acesso" → escolher o pendrive (uma vez por pendrive) |
| 11+ | seletor de pastas | idem |
| sem seletor de pastas e sem leitura direta | — | bloco técnico: "este Android não deixa o Mostraí ler o pendrive"; atualizar como no 3.0.0 (abrir o APK pelo gerenciador de arquivos da TV) |

Se a leitura direta funciona na TCL é **o** item físico mais incerto
(`docs/hardware/tcl-32s6500s.md`).

## Validação (antes de qualquer modal)

O APK é copiado para a área privada (`filesDir/atualizacao/`), com o
SHA-256 calculado durante a cópia, e conferido **na cópia** — o pendrive
pode sair depois disso. Em ordem:

1. o Android lê o arquivo como APK (`getPackageArchiveInfo`) — senão
   "APK inválido ou corrompido";
2. `update.json` legível e SHA-256 igual ao da cópia (se houver JSON);
3. pacote `br.com.mostrai.player` — senão "não é do Mostraí Player:
   ignorado";
4. o Player **instalado** está assinado com a chave definitiva — senão o
   updater fica desligado (um build de bancada nunca é "atualizado" por
   um oficial; o Android recusaria);
5. o APK está assinado com **exatamente** a chave definitiva
   (`scripts/certificado-producao.sha256`, embutida no build) — senão
   "outra assinatura: recusado";
6. `update.json` coerente com o APK (versionCode, certificado);
7. `versionCode` maior que o instalado — igual: "Esta versão já está
   instalada."; menor: "Versão do USB anterior à instalada.".

Espaço: copiar não pode levar o disco abaixo da mesma reserva do cache de
mídia, conferida antes e a cada 8 MB — nada é apagado para abrir espaço.
APK acima de 300 MB é recusado sem ler. Pendrive arrancado no meio, erro
de leitura, cópia menor que o anunciado: falha segura, nenhum arquivo
parcial fica, a versão atual segue.

Recusas (menos "mesma versão" e pendrive sem pacote) vão ao diário
(`ATUALIZACAO_RECUSADA`). Nenhuma mensagem mostra caminho, hash ou
certificado na tela — o bloco técnico mostra versão e resultado.

## Instalação

Quem instala é o **instalador do Android**: `ACTION_VIEW` com
`application/vnd.android.package-archive`, URI `content://` do
`FileProvider` (`br.com.mostrai.player.atualizacao`, só a pasta
`atualizacao/`), `FLAG_GRANT_READ_URI_PERMISSION`. Nunca `file://`,
nunca instalação silenciosa, root, `adb` ou Device Owner.

- Sem "instalar apps desconhecidos" para o Mostraí
  (`canRequestPackageInstalls()` falso): modal "Para atualizar o Mostraí
  pelo pendrive, permita instalações pelo Mostraí Player." → [Permitir
  atualizações] abre a tela do Android para o próprio app; ao voltar, o
  modal reaparece.
- Durante o instalador, as Configurações e o seletor, o **watchdog pausa**
  o retorno rápido (teto de 10 min) — senão puxaria o Player por cima da
  confirmação. Voltar à frente desfaz a pausa.
- Cancelou ou o Android recusou: o Player volta na versão atual, bloco
  técnico "Instalação não concluída".
- Instalou: o Android entrega `MY_PACKAGE_REPLACED` à versão nova, que
  apaga a cópia, registra "Última atualização" e o diário (`ATUALIZADO`),
  rearma o watchdog e abre o Player. Credencial, ID, preferências, bancos
  e cache moram na área do app e ficam (mesma assinatura).

## Depois

"Depois" (botão, VOLTAR ou 2 min sem resposta) não reoferece o mesmo
pendrive enquanto ele estiver conectado. Volta a oferecer quando: o
pendrive é retirado e reconectado; o processo reinicia (reboot); o
técnico aperta **Verificar USB** ou **Instalar atualização** no bloco
técnico (VOLTAR → tela do PIN).

## Bloco técnico — seção ATUALIZAÇÃO

Versão instalada · Permissão para instalar (concedida/não) · USB conectado
(e o acesso: leitura permitida, seletor disponível…) · Versão encontrada ·
Última verificação · Último resultado · Última atualização · Ligar tela.
Ações: [Verificar USB] · [Instalar atualização] (só com cópia validada) ·
[Testar ligar tela (2 min)].

## Limites

- Robolectric prova a lógica (detecção, cópia, validação, modal, Intent,
  pausa, `MY_PACKAGE_REPLACED`) com volumes e APKs simulados; **não**
  prova o firmware da TCL: se o pendrive é legível, que tela de
  "instalar apps" ela tem, como o instalador dela se comporta.
- O 3.0.0 não tem updater: 3.0.0 → 3.0.1 é sideload manual pelo
  gerenciador de arquivos da TV (mesma chave, dados ficam).
- Testar o modal exige uma versão **acima** da instalada: o APK de teste
  `versionCode 7` (`scripts/release-teste-n-mais-1.sh 7` +
  `preparar-usb-update.sh --teste`). Depois desse teste a TV fica em 7 e
  só volta à oficial desinstalando — e **a próxima versão oficial tem de
  ser `versionCode 8` ou mais**.
