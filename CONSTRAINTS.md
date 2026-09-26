# CONSTRAINTS — o que este projeto não faz

Escopo negativo e limites assumidos. Ver `docs/specs/2026-09-21-mostrai-player.md`
para o porquê de cada decisão de escopo.

## Vetos formais (não se negociam, ver `docs/specs/`)

- **Nunca usuário e senha na TV.** A tela se autentica só por chave de
  aparelho revogável, obtida trocando o ID da tela + código de instalação
  do admin. Qualquer proposta de tela de login é rejeitada de saída.
- **Nenhum segredo versionado.** Chave do aparelho, keystore de assinatura,
  senhas e credenciais ficam fora do Git (`.gitignore`). O keystore de
  release fica com o dono, fora do repositório. A URL de produção
  (`https://mostrai.sancocore.com.br`) **não** é segredo: é o endereço
  público do serviço, fixo no APK por decisão de produto (`Produto.kt`).
- **A tela não decide nada sozinha.** Hora, ordem e o que conta são do
  servidor. O app executa e relata — nunca infere crédito, nunca decide
  elegibilidade.
- **Só conclusão real conta.** A exibição vira comprovante apenas em
  `STATE_ENDED`. Sem limiar de percentual, sem "tocou 90%, vale".
- **O relógio da TV nunca decide negócio.** Toda decisão de crédito, janela e
  item vem do backend (`janelaId`, `itemProgramacaoId`, `servidorAgora`).
  Onde o relógio de parede local é usado (agendar quando acordar na virada da
  hora), é só para *quando* agir, nunca para *o quê* creditar — documentado
  no ponto de uso (`PlayerActivity.agendarViradaDeHora`).

## Fora do MVP (2.0.0)

Se não é necessário para instalar, reproduzir, ficar offline, comprovar,
receber config, ajustar margens, respeitar horário, sair com PIN ou se
recuperar, não entra. Em especial, **não** reintroduzir:

- **OTA** (atualização remota). Atualizar é sideload de um APK assinado com a
  mesma chave (`RUNBOOK.md`).
- **Device Owner, MDM, lock task, launcher `HOME`.** O `HOME` foi provado
  incompatível com o instalador da TCL (`docs/erros/2026-09-25-…`). Quem
  traz o player de volta é o `Watchdog`.
- **Painel técnico na TV** (gesto de 3 toques, tela de diagnóstico). O único
  diálogo local é o PIN de saída.
- **Provisionamento por JSON, pendrive, `BuildConfig` por tela ou extras de
  ADB.** A única forma é ID da tela + código de instalação digitados na TV.
- **`baseUrl` variável, multi-host, rotação configurável ou
  multi-orientação.** URL e rotação são constantes de `Produto.kt`. Se a TV
  mostrar de ponta-cabeça, a correção é uma build nova com 270°.
- **Contrato V1, `/hello`, rotação de credencial.** O backend não fala nada
  disso (`player-mvp-contract.md`).
- **WebSocket, SSE, comandos remotos, telemetria sofisticada** (Sentry,
  analytics). O heartbeat de 15 s com `estado`/`erro`/`fila` é todo o
  sinal que o servidor recebe.
- **Relatório na TV** e **login de usuário** (este último vetado sempre).
- **Cache com eviction sofisticada.** Teto de 1 GB, descarte do mais antigo.

## Exceção de classificação (estação 1)

Este projeto **não é nenhum dos quatro tipos** que `classificar`/`construir`
enumeram (Institucional, SaaS, E-commerce, PWA) — é um app Android TV nativo,
sideload, sem interface web. Consequências assumidas:

- A estação 5 **não lê** `construir/references/desenvolvimento-web.md` nem
  `tipo-*.md` como checklist de fechamento — esses catorze itens (`<head>`,
  SEO técnico, formulário, e-mail, formato brasileiro) não existem neste
  produto. O checklist de fechamento da estação 5 é o mapa de blocos MVP do
  prompt original (seção 3), replicado em "Estado na esteira" do `CLAUDE.md`.
- **Cloudflare Access não se aplica.** Não há área administrativa no app; o
  único diálogo local é o PIN de saída.
- **"A versão inicial no ar" (fechamento da estação 5) não tem o mesmo
  sentido de URL que responde.** Para um app sideloaded, o equivalente é
  instalado e rodando de verdade num aparelho real. Esta sessão não tem
  acesso a hardware Android TV nem a um emulador viável (ambiente sem
  `/dev/kvm`, sem aceleração de virtualização — emulador Android TV sem
  aceleração é impraticável). A verificação "no ar" desta estação fica como
  pendência do dono, registrada em `docs/pendencias.md`, não como corte de
  canto desta sessão.

## Limites assumidos (Lei 7)

- **Fila de proof-of-play**: até 50.000 eventos pendentes por aparelho
  (`FilaProofOfPlay.TAMANHO_MAXIMO_FILA`). Acima disso, descarta o mais
  antigo e conta a perda. Aviso no diário a partir de 10.000.
- **Lote de `/played`**: 50 eventos por requisição (o contrato aceita até
  500; lote pequeno mantém o corpo longe dos 100 KB).
- **Espera de reenvio**: 5 s → 15 s → 60 s → 5 min → 15 min → teto de 30 min,
  nunca desistência. 429 respeita `Retry-After` (1 s a 1 h).
- **Horizonte local**: 7 dias + 1 h. O servidor aceita até 7 dias depois do
  fim da janela; a hora a mais cobre a própria janela. Medido pelo relógio
  de parede — contabilidade local de descarte, não decisão de negócio.
- **Cache de mídia**: 1 GB.
- **PIN de saída**: 3 erros → bloqueio de 5 s, dobrando até 5 min.

## Dependências (proporcionalidade, Lei 0)

Nenhuma dependência de rede HTTP nem de banco externo foi adicionada de
propósito: cliente HTTP é `HttpURLConnection` (JDK), a fila durável é SQLite
puro via `SQLiteOpenHelper` (sem Room). Menos dependência é menos risco de
resolução numa TV com internet de comércio, e o esquema é pequeno o
suficiente para não precisar de ORM. `kotlinx-coroutines-android` e
`androidx.lifecycle:lifecycle-runtime-ktx` foram aceitos por serem
ferramentas de concorrência padrão do ecossistema Android/Kotlin, não
dependências de negócio.

## Riscos de segurança aceitos

- **A chave do aparelho fica em texto claro no armazenamento privado do
  app.** Extraí-la exige acesso root ou físico à TV. A chave é revogável por
  tela no admin: o pior caso é uma tela, não a rede.
- **O APK de teste físico é debug.** Assinado com a chave de debug — não é
  produção e não deve ir para cliente real (`RUNBOOK.md`).

## CI (estação 3)

Sem verificação automática de segurança dedicada (SAST, scanner de
dependência) nesta versão — desproporcional a um app sem dado pessoal, sem
pagamento e sem superfície web exposta (Lei 0). Mantido: build + testes
unitários automáticos a cada push (`.github/workflows/ci.yml`), que é o piso
que nenhum projeto pula.
