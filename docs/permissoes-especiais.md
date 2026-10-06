# Permissões — auditoria do Player 3.0.1

06/10/2026. Toda permissão do manifesto tem decisão registrada aqui;
`GuardaMvpTest` e `scripts/verificar-apk.sh` falham com qualquer outra.
Regra: nenhuma permissão "por comodidade" — só a que um recurso decidido
exige, e só no Android em que ele exige.

## Declaradas (7)

| Permissão | Tipo | Para quê | Desde |
|---|---|---|---|
| `INTERNET` | normal | falar com o backend | 1.0 |
| `ACCESS_NETWORK_STATE` | normal | saber se há rede (cartão "sem conexão" só no bloco técnico) | 1.0 |
| `RECEIVE_BOOT_COMPLETED` | normal | abrir o Player quando a TV liga | 1.0 |
| `SYSTEM_ALERT_WINDOW` | especial | **só** exceção ao bloqueio de abrir Activity do segundo plano no Android 10+ (retorno do watchdog, boot). Nunca desenha sobre outros apps. Na TCL (Android 8) não é usada | 3.0.0 |
| `REQUEST_INSTALL_PACKAGES` | especial ("Instalar apps desconhecidos") | entregar o APK do pendrive ao instalador do Android, que pede a confirmação. Concedida por pessoa, uma vez por TV | 3.0.1 |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion 29`) | perigosa (pedida em tempo de execução) | ler o pendrive direto até o Android 10 — a TCL é Android 8. Pedida **só** quando o técnico aperta "Permitir acesso" no modal | 3.0.1 |
| `WAKE_LOCK` | normal | **só** o teste manual de ligar a tela (`kiosk/LigarTela.kt`) | 3.0.1 |

O manifesto mesclado tem ainda
`br.com.mostrai.player.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, de
assinatura, que o androidx.core declara para o próprio app.

## Permissões especiais auditadas

| Permissão especial | Classe | Decisão |
|---|---|---|
| **Instalar apps desconhecidos** (`REQUEST_INSTALL_PACKAGES`) | **NECESSÁRIA** (3.0.1) | Sem ela não há atualização por pendrive. O Player confere com `canRequestPackageInstalls()`; sem a liberação mostra "Para atualizar o Mostraí pelo pendrive, permita instalações pelo Mostraí Player." e abre a tela do Android para o próprio app (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` com `package:`; se o firmware não tiver, a de segurança ou a geral). Na TCL (Android 8.0) a liberação já é por app. |
| **Exibir sobre outros apps** (`SYSTEM_ALERT_WINDOW`) | **NECESSÁRIA só no Android 10+**; desnecessária na TCL | Exceção oficial ao bloqueio de abrir Activity do segundo plano (`docs/android-modernizacao.md`). Sem ela, no 10+, o retorno automático fica bloqueado e o bloco técnico diz isso. |
| **Acesso a todos os arquivos** (`MANAGE_EXTERNAL_STORAGE`) | **DESNECESSÁRIA — não declarada** | Não existe no Android 8 (a TCL). No 11+ o pendrive é lido pelo seletor do Android (Storage Access Framework), com a pasta autorizada uma vez pelo técnico — sem acesso ao armazenamento inteiro. Pedir "todos os arquivos" seria só comodidade. **Risco aceito:** TV Android 11+ sem o seletor de pastas (muitos firmwares de TV não trazem o DocumentsUI) não consegue ler o pendrive; o bloco técnico diz "seletor de pastas indisponível". Se aparecer TV assim no parque, decidir então entre esta permissão (exceção registrada) e o sideload manual. |
| **Otimização de bateria** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | **DESNECESSÁRIA** | TV fica na tomada: o Android libera app standby e Doze enquanto o aparelho está carregando/ligado à energia. Na frente, o Player mantém a tela com `FLAG_KEEP_SCREEN_ON`. Nenhum trabalho de fundo depende de isenção. |
| **Acesso ao uso** (`PACKAGE_USAGE_STATS`) | **DESNECESSÁRIA** | Serviria para saber que app está na frente; o Player sabe pelo próprio ciclo de vida (`onStart`/`onStop` → `Watchdog.naFrente`/`saiuDaFrente`). |
| **Modificar configurações do sistema** (`WRITE_SETTINGS`) | **DESNECESSÁRIA** | Serviria para tempo de tela/brilho. A tela fica acesa com `FLAG_KEEP_SCREEN_ON`; a "suspensão automática" de 4/6/8 h da TCL é configuração do fabricante, fora do alcance de `WRITE_SETTINGS` — desligada à mão (checklist físico). |
| **Alarmes e lembretes** (`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`) | **DESNECESSÁRIA** | `setExact` até o Android 11; `set` do 12 em diante — o primeiro retorno (5 s) não é adiado pelo AlarmManager (decisão da 3.0.0). |
| **Ligar a tela** (`TURN_SCREEN_ON`, API 34+) | **OPCIONAL — não declarada** | A TCL (Android 8) não tem essa permissão; o teste manual usa `WAKE_LOCK` com `ACQUIRE_CAUSES_WAKEUP` e "turn screen on" da Activity, que é o caminho do Android 8. O efeito dela sobre `ACQUIRE_CAUSES_WAKEUP` no Android 14+ e o nível de proteção **não foram confirmados nesta sessão** (a página de referência não pôde ser lida). Só reavaliar se o teste físico na TCL passar **e** houver TV Android 14+ no parque. |

## O que o updater **não** usa

- `INSTALL_PACKAGES` (privilegiada), `PackageInstaller` em sessão,
  `REQUEST_DELETE_PACKAGES`, root, `adb`, Device Owner — quem instala é o
  instalador do Android, com confirmação da pessoa.
- `WRITE_EXTERNAL_STORAGE`: o pendrive só é lido; a cópia vai para a área
  privada do app (`filesDir/atualizacao`).
- `QUERY_ALL_PACKAGES`: a única consulta de pacote é "existe seletor de
  pastas?", declarada em `<queries>` (`OPEN_DOCUMENT_TREE`).
- Rede: o pacote `atualizacao/` não fala com o backend (`GuardaMvpTest`).

## Ligar a tela — estado

Capacidade **a provar** na TCL, não recurso em uso. TV **sem energia**
não liga por app nenhum; TV em **standby** talvez — depende de o firmware
manter o processador e o relógio de despertar vivos. O Player 3.0.1:

- **não** acende a tela sozinho (horário de funcionamento e watchdog
  continuam sem acordar TV em standby);
- tem um botão no bloco técnico, "Testar ligar tela (2 min)", que agenda
  um alarme `ELAPSED_REALTIME_WAKEUP`, pede a tela com wake lock
  `ACQUIRE_CAUSES_WAKEUP` por 15 s e abre o Player com "turn screen on"
  (mantido até a medição, 5 s depois de a Activity voltar à frente — só
  então a janela teve chance de acender o painel);
- registra o que o Android disse (alarme na hora ou atrasado, tela no
  disparo, tela depois) no bloco técnico e no diário (`LIGAR_TELA_TESTE`).

Ligar automático no começo do horário só depois de o checklist físico
(seção "Ligar a tela") passar na TCL — e então como decisão nova.
