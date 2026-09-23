# Contrato Player V2 — Mostraí

Contrato **implementado** no Mostraí Player, não proposta. Tudo aqui existe
no código deste repositório e está coberto por teste unitário; onde algo
ainda não existe do lado do backend, está marcado como tal.

Versão do contrato: **2** (`X-Player-Contract: 2`).
Player de referência: `versionName 1.0.0`, `versionCode 2`.

> **Regra que vale para o documento inteiro:** o player nunca depende de uma
> rota V2 existir. Um `404` numa rota V2 é lido como "este backend é V1" — o
> player marca a indisponibilidade, segue no comportamento antigo e volta a
> tentar no ciclo seguinte. Nenhuma atualização do servidor exige release do
> app, e nenhuma tela quebra porque o backend ainda não foi atualizado.

---

## 1. Autenticação

Toda requisição autenticada leva:

| Header | Valor | Observação |
|---|---|---|
| `X-Aparelho-Id` | chave do aparelho | nome legado, mantido durante a migração |
| `X-Aparelho-Key` | chave do aparelho | nome novo, mesmo valor |
| `X-Player-Version` | `1.0.0+2` | `versionName+versionCode` |
| `X-Player-Contract` | `2` | |

Os dois headers de credencial carregam **o mesmo valor**. O backend pode
passar a ler apenas `X-Aparelho-Key` quando quiser; o player não precisa de
release para isso.

**O backend deve rejeitar credencial vazia.** O player nunca manda header de
credencial em branco — sem chave utilizável ele simplesmente não faz a
requisição (`ConfigAparelho.temCredencial`). Se chegar um header vazio, não
veio deste player.

### 1.1 Rotação de credencial

1. O backend devolve `novaChave` na resposta do heartbeat.
2. O player guarda como **candidata**, sem descartar a atual.
3. A candidata passa a ser enviada nos headers.
4. Na **primeira resposta bem-sucedida** com a candidata, ela é promovida a
   oficial e a antiga é descartada.
5. Em `401`/`403` com a candidata, ela é descartada e a antiga volta a valer.

Só conta a resposta de uma requisição que **levou** a candidata. Playlist,
heartbeat, config e proof-of-play rodam em paralelo; um `200` ou `401` de
uma requisição que saiu com a chave antiga não promove nem descarta a
candidata (auditoria, BUG-013).
6. Em falha de rede, nada muda — nem promove, nem descarta.

O backend precisa aceitar **as duas chaves** durante a janela de
sobreposição (24h basta). Sem isso, uma tela que perca a rede no meio da
troca fica sem credencial válida.

---

## 2. Provisionamento

### 2.1 Formato preferencial — token de uso único

`mostrai-config.json` na raiz do pendrive:

```json
{
  "baseUrl": "https://api.mostrai.com.br",
  "tokenProvisionamento": "tok_a1b2c3d4",
  "rotacaoTela": 90
}
```

O player troca o token por credenciais:

```
POST /player/provisionar
Content-Type: application/json
X-Player-Version: 1.0.0+2
X-Player-Contract: 2

{"tokenProvisionamento": "tok_a1b2c3d4"}
```

Resposta esperada:

```json
{"dispositivoId": "tela_123", "chaveAparelho": "key_xyz789"}
```

| Código | O que o player faz |
|---|---|
| `200` com os dois campos | grava credenciais, **apaga o token**, marca backend V2 disponível |
| `200` sem algum campo | trata como resposta inválida, mantém o token |
| `404` | marca backend V1, **mantém o token** para tentar depois |
| rede/5xx | mantém o token |

O token **só é apagado depois do sucesso**. Descartá-lo numa falha
transitória transformaria "backend ainda não atualizado" numa TV que precisa
de visita técnica.

**Do lado do backend:** o token deve ser de uso único e ser queimado na
troca. É isso que permite o pendrive não carregar o segredo definitivo — um
pendrive perdido expõe um token já inutilizado.

### 2.2 Formato legado — ainda suportado

```json
{
  "dispositivoId": "tela_123",
  "chaveAparelho": "key_xyz789",
  "baseUrl": "https://api.mostrai.com.br",
  "pin": "4821",
  "margemVminTopo": 2.5,
  "margemVminBase": 2.5,
  "margemVminEsquerda": 2.5,
  "margemVminDireita": 2.5,
  "rotacaoTela": 90
}
```

Continua funcionando sem nenhuma mudança no backend. É o caminho para lançar
o primeiro ponto antes de `/player/provisionar` existir.

### 2.3 Troca do token — garantias do lado do player

- Credenciais e remoção do token são gravadas numa escrita única e síncrona:
  um crash logo depois da troca não deixa o aparelho com o token queimado e
  sem chave (auditoria, ROB-003).
- Token que chega com o player já rodando (pendrive lido depois da
  permissão, bancada) é trocado na hora, sem esperar o heartbeat.
- **Pedido ao backend:** se a resposta da troca se perder na rede depois de
  o servidor queimar o token, o aparelho não tem como se recuperar sozinho.
  Aceitar o mesmo token de novo por alguns minutos, devolvendo as mesmas
  credenciais, fecha esse buraco.

### 2.4 Provisionamento de bancada por Intent (`adb`)

Extras de Intent na `PlayerActivity` só provisionam aparelho **ainda não
provisionado** no APK de release. A Activity é exportada (launcher/HOME) e o
Android não informa quem a abriu; aceitar extras numa tela em operação
deixaria qualquer app da TV trocar o `baseUrl` e receber a chave no header
(auditoria, BUG-023). No APK de depuração, sobrescrevem sempre.

---

## 3. `POST /player/{dispositivoId}/hello`

Dados técnicos que **não mudam**. Enviado no boot, depois de um
provisionamento, e quando qualquer um dos valores muda (atualização do app
ou do firmware). Não vai no heartbeat de propósito: repetir isso a cada 5
minutos seriam ~105 mil envios por ano, por tela, do mesmo texto.

```json
{
  "contrato": 2,
  "versaoApp": "1.0.0",
  "buildNumber": 2,
  "fabricante": "TCL",
  "modelo": "32S6500S",
  "android": "8.0.0",
  "largura": 1920,
  "altura": 1080,
  "timezone": "America/Sao_Paulo"
}
```

Resposta opcional (poupa um ciclo de heartbeat):

```json
{"configVersion": 184}
```

| Código | O que o player faz |
|---|---|
| `2xx` | grava a assinatura para não reenviar, sincroniza config se vier versão |
| `404` | marca backend V1 e segue |
| outros | tenta de novo depois do próximo heartbeat que confirmar o V2 |

Sem resposta no boot (TV ligada sem internet), o `hello` é repetido depois
de um heartbeat `2xx` — nunca num backend V1, onde seria um `404` extra por
ciclo. Sem mudança na assinatura, não há requisição.

---

## 4. `POST /player/{dispositivoId}/heartbeat`

A cada **5 minutos**, e **imediatamente no boot** (não espera o primeiro
intervalo — antes disso toda tela ficava invisível para o admin durante o
boot inteiro).

### 4.1 Requisição

```json
{
  "versaoContrato": 2,
  "estado": "PLAYING",
  "configVersionAplicada": 183,
  "criativoId": "cri_456",
  "ultimaPlaylistOkEm": "2026-09-23T10:02:11-03:00",
  "fila": {
    "pendentes": 412,
    "maisAntigoEm": "2026-09-22T08:00:00-03:00"
  },
  "erro": {
    "codigo": "PLAYBACK_FALHOU",
    "ocorreuEm": "2026-09-23T03:14:00-03:00",
    "mensagem": "ERROR_CODE_DECODING_FAILED"
  },
  "desvioRelogioMs": -4200,
  "update": {"estado": "READY"}
}
```

| Campo | Obrigatório | Descrição |
|---|---|---|
| `versaoContrato` | sim | sempre `2` |
| `estado` | sim | ver 4.2 |
| `configVersionAplicada` | sim | `0` = nunca recebeu config |
| `criativoId` | não | omitido quando não há item comercial no ar |
| `ultimaPlaylistOkEm` | não | ISO 8601 com offset; ausente = nunca buscou com sucesso |
| `fila.pendentes` | sim | eventos **terminados e não enviados**; não conta órfão nem quarentena |
| `fila.maisAntigoEm` | sim (pode ser `null`) | do mais antigo aguardando envio |
| `erro` | sim (pode ser `null`) | `null` explícito significa "sem erro", não "não reporto" |
| `desvioRelogioMs` | não | relógio da TV menos relógio do servidor; negativo = TV atrasada |
| `update.estado` | não | ver 8.2 |

**`erro` é durável, com dois limites que o admin precisa conhecer**
(auditoria, DOC-001):

- Vem de uma tabela SQLite, não de memória: um erro **registrado** sobrevive
  a reinício do app e a queda de energia.
- É limpo quando uma playlist é buscada do servidor com sucesso — e isso
  acontece logo no boot. Um erro registrado antes de um reinício pode, então,
  ser limpo antes do primeiro heartbeat do boot seguinte. Erro que se repete
  (reprodução, hash, autenticação) volta a ser registrado e aparece; erro
  pontual (uma falha de OTA a cada 6h) pode aparecer só por alguns minutos.
- **Crash do app não é registrado.** Não há tratador global gravando no
  diário. Um app que cai e volta aparece como tela que sumiu e voltou
  (`last_seen_at`), não como "Erro do player".

Se o banco local não abrir (disco cheio, arquivo ilegível), o diário fica
mudo e `erro` vai `null`; a tela continua tocando (auditoria, BUG-004).

### 4.2 Estados

| Estado | Significado |
|---|---|
| `PLAYING` | exibindo mídia comercial |
| `IDLE` | provisionado, sem erro, sem item comercial no ar |
| `OUT_OF_SCHEDULE` | fora do horário operacional (ver seção 7) |
| `NO_PLAYLIST` | sem playlist utilizável, nem do servidor nem do cache |
| `DOWNLOAD_ERROR` | mídia não baixou, ou baixou e o hash não conferiu |
| `PLAYBACK_ERROR` | o player falhou ao reproduzir |
| `AUTH_ERROR` | credencial recusada pelo servidor |
| `NOT_PROVISIONED` | aparelho sem identidade |
| `CONFIG_ERROR` | config recebida não pôde ser aplicada |
| `UPDATE_PENDING` | atualização baixada e verificada, esperando confirmação |

### 4.3 Resposta

```json
{
  "servidorAgora": "2026-09-23T13:00:00Z",
  "configVersion": 184,
  "playlist": {"atualizar": true},
  "update": { },
  "novaChave": null,
  "margens": {"superior": 2.5, "direita": 1, "inferior": 2.5, "esquerda": 1}
}
```

Todos os campos são **opcionais**. Uma resposta `{"ok": true}` é válida e
não pede nada.

| Campo | Efeito no player |
|---|---|
| `servidorAgora` | referência de relógio |
| `configVersion` | se diferente de `configVersionAplicada`, busca `GET /config` |
| `playlist.atualizar` | busca a playlist agora, com debounce (ver 6.2) |
| `update` | ver seção 8 |
| `novaChave` | vira candidata (ver 1.1) |
| `margens` | **compatibilidade V1** — aplicada se não veio config nova |

> **Nota sobre `margens` no heartbeat.** É o contrato de hoje (migration 069)
> e continua funcionando. Quando `/config` passar a entregar margens, ela
> vence por ser aplicada depois. Recomendação: mover margens para `/config` e
> manter as duas durante a transição, para não haver duas fontes de verdade
> em definitivo.

### 4.4 Códigos

| Código | O que o player faz |
|---|---|
| `2xx` | aplica a resposta, marca backend V2 disponível |
| `401`/`403` | estado `AUTH_ERROR`, registra erro durável, descarta chave candidata |
| `404` | marca backend V1, segue no comportamento antigo |
| `429` | respeita `Retry-After`; não registra erro |
| `5xx` | tenta no próximo ciclo; **não** registra erro durável |
| rede/timeout | idem `5xx` |

`5xx` e falha de rede não sujam o diário de propósito: rede caindo numa loja
é rotina, e o diário existe para o que o operador precisa investigar.

---

## 5. `GET /player/{dispositivoId}/config`

Buscado **só quando** `configVersion` do heartbeat/hello difere de
`configVersionAplicada`.

```json
{
  "configVersion": 184,
  "margens": {"superior": 2.5, "direita": 1, "inferior": 2.5, "esquerda": 1},
  "rotacaoTela": 90,
  "pinPainel": "4821",
  "versaoMinimaBuild": 2,
  "operacao": {
    "regime": "CUSTOM",
    "timezone": "America/Sao_Paulo",
    "porDiaDaSemana": {
      "seg": [{"inicio": "09:00", "fim": "18:00"}],
      "sab": [{"inicio": "09:00", "fim": "13:00"}]
    },
    "feriados": {
      "2026-12-25": [],
      "2026-12-24": [{"inicio": "09:00", "fim": "14:00"}]
    }
  },
  "update": {"baixarAutomaticamente": true, "horasEntreTentativas": 6},
  "cache": {"tetoMegabytes": 1024}
}
```

| Campo | Obrigatório | Notas |
|---|---|---|
| `configVersion` | **sim** | inteiro ≥ 0. Sem ele a config inteira é descartada |
| `margens` | não | vmin, termos visuais, cada lado limitado a 0–10 |
| `rotacaoTela` | não | só `0`, `90`, `180`, `270`; outro valor é ignorado |
| `pinPainel` | não | exatamente 4 dígitos; fora disso é ignorado |
| `versaoMinimaBuild` | não | lido e guardado, **sem efeito no player hoje** (informativo) |
| `operacao` | não | ver seção 7 |
| `update` | não | `baixarAutomaticamente`; `horasEntreTentativas` (1–72, padrão 6) é a janela de silêncio depois que o operador cancela a instalação |
| `cache.tetoMegabytes` | não | reservado |

**Regras de aplicação:**

- **Campo ausente = não mexa.** Nunca significa "zere". É a generalização de
  RN-17 e é o que permite o backend evoluir a config sem coordenar release.
- **Atômica na leitura.** Ou o corpo inteiro é válido e é aplicado, ou nada
  é escrito. A versão aplicada é gravada por último: se o processo morrer no
  meio da aplicação, o player nunca afirma uma versão que não aplicou, e a
  próxima sincronização refaz.
- **Uma sincronização por vez.** Dois heartbeats simultâneos não aplicam uma
  config antiga por cima da nova (auditoria, BUG-010). Não existe regra de
  "versão só sobe": o backend pode reiniciar a numeração.
- **Falha preserva o último válido.** Se `/config` cair, a tela continua
  exatamente como estava. O único efeito visível é o admin mostrar
  "configuração pendente" até o próximo ciclo.
- O corpo bruto é persistido; a config sobrevive a reboot sem rede.

| Código | O que o player faz |
|---|---|
| `2xx` com `configVersion` | aplica, grava, registra `CONFIG_APLICADA` |
| `2xx` sem `configVersion` | descarta, registra `CONFIG_FALHOU` |
| `404` | marca backend V1 |
| outros | mantém o último válido, registra `CONFIG_FALHOU` |

> **Armadilha de UX a evitar no admin.** Com heartbeat de 5 minutos, toda
> alteração vai aparecer como "pendente" por até 5 minutos, sempre. Sugestão:
> só mostrar pendência depois de ~2 ciclos, senão o indicador vira ruído e
> perde a função de sinalizar problema real.

---

## 6. Playlist

### 6.1 `contentHash`

O item da playlist ganha um campo **opcional**:

```json
{
  "itemProgramacaoId": "ip_789",
  "criativoId": "cri_456",
  "contentHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "url": "https://cdn.mostrai.com.br/criativos/456.mp4",
  "duracaoSegundos": 15,
  "anuncianteId": "anu_1",
  "autoanuncio": false,
  "institucional": false,
  "contabiliza": true
}
```

SHA-256 do arquivo, hexadecimal, 64 caracteres. Maiúsculas são aceitas e
normalizadas; qualquer outro formato é ignorado e o player cai no
comportamento antigo.

**Por que isso importa.** Sem `contentHash`, o cache usa `criativoId` como
identidade física e depende da garantia contratual de que `criativoId → url`
é imutável. Se essa promessa for quebrada, a tela serve o arquivo antigo
**indefinidamente** — não há TTL nem revalidação. Com hash, o arquivo novo
simplesmente tem outro nome, e o download é verificado antes de ser aceito.

Comportamento do player:

| Situação | Resultado |
|---|---|
| hash presente e confere | arquivo vai para o cache como `sha256-<hash>` |
| hash presente e **não** confere | arquivo apagado, item **pulado**, estado `DOWNLOAD_ERROR` |
| hash ausente | comportamento V1 (`criativo-<id>` ou `url-<sha da url>`) |
| dois criativos, mesmo hash | compartilham um único arquivo |

Hash divergente **não** cai para tocar da URL remota: seria servir
exatamente o arquivo que acabou de ser rejeitado. A rejeição vale por **30
minutos** (em memória): o item não é rebaixado a cada vez que aparece na
playlist, e depois disso o player tenta de novo, para pegar a correção
quando vier (auditoria, BUG-006).

Sem `contentHash`, uma resposta `2xx` cujo `Content-Type` seja texto, HTML
ou JSON **não** vira cache — é o caso do portal cativo de Wi-Fi que responde
uma página para qualquer URL (auditoria, BUG-020). Sirva mídia com um tipo
de mídia (`video/*` ou `application/octet-stream`).

Redirecionamento de `http` para `https` não é seguido pelo cliente HTTP do
Android nem pelo ExoPlayer: sirva as URLs de mídia já em `https`.

`criativoId` continua sendo a identidade de domínio e continua indo no
proof-of-play. Ele só deixa de ser chave de conteúdo.

### 6.2 Refresh sinalizado

`playlist.atualizar: true` no heartbeat faz o player buscar a playlist na
hora, reduzindo a latência de até 15 minutos para até 5.

O player protege contra enxurrada: uma busca por vez
(`PlayerActivity.buscandoPlaylist`), e os gatilhos normais (boot, 15 min,
virada de hora com jitter de 0–29s derivado da chave) continuam valendo.

O backend deve mandar `atualizar: true` **uma vez** após uma mudança, não em
todo heartbeat.

### 6.3 Playlist vazia

Lista de itens vazia (envelope com `"itens": []` ou `[]` no formato antigo)
mostra a tela institucional. Com um anúncio no ar, ele termina antes
(auditoria, BUG-022). Para "sem programação nesta hora", prefira o item
institucional explícito (RN-14).

Se todos os itens falharem numa volta completa (reprodução ou hash), a tela
institucional aparece por 10 s antes de uma nova tentativa (auditoria,
BUG-005).

### 6.4 O que não mudou

Envelope, `janelaId`, `janelaInicio`, `janelaFim`, `servidorAgora`,
`itemProgramacaoId`, `criativoId`, `contabiliza`, modo degradado por array
puro — tudo idêntico ao contrato atual. As duas garantias continuam valendo:

1. `itemProgramacaoId` deriva da posição na sequência congelada da hora,
   nunca do índice do array.
2. `criativoId → url` é imutável **enquanto não houver `contentHash`**. Com
   hash, essa garantia deixa de ser necessária.

---

## 7. Horário operacional

Vive **inteiro no aparelho**: a loja continua abrindo e fechando no horário
de sempre quando a internet cai.

| Regime | Comportamento |
|---|---|
| `HORAS_24` | nunca apaga. É o padrão |
| `FOLLOW_POINT` | usa as faixas entregues (do ponto) |
| `CUSTOM` | usa as faixas próprias da tela |

- Faixas em `HH:MM` ou `HH:MM:SS` (segundos ignorados — é como uma coluna
  `time` do Postgres sai em JSON), minuto de início **inclusivo**, fim
  **exclusivo** (auditoria, BUG-021).
- `"sex": [{"inicio": "22:00", "fim": "02:00"}]` cruza a meia-noite: vale
  sexta das 22:00 às 24:00 **e sábado** das 00:00 às 02:00. A madrugada
  pertence ao dia seguinte (auditoria, BUG-026).
- Dia da semana sem faixa = fechado naquele dia (mas a madrugada de uma
  faixa da véspera ainda vale).
- Dia cujas faixas existem mas nenhuma pôde ser lida = **aceso o dia
  inteiro** (dado ruim nunca apaga a tela).
- `feriados` sobrepõe o dia inteiro, inclusive a madrugada que viria da
  véspera. Lista vazia = fechado o dia inteiro.
- O horário é avaliado no relógio da TV, no fuso configurado. Um relógio
  errado na TV desloca o horário; `desvioRelogioMs` no heartbeat mostra
  isso.
- Chaves de dia aceitas: `seg|segunda|mon|monday|1` … (ver
  `HorarioOperacional.diaDeTexto`).

**Padrão deliberadamente permissivo:** sem dados de horário — regime não
configurado, faixas vazias, timezone inválida — o player **acende**. Tela
acesa fora de hora é desperdício; tela apagada em horário comercial por
causa de config que não chegou é receita perdida e reclamação do dono do
ponto.

Fora do horário: nenhum item comercial toca, **nenhum proof-of-play nasce**,
a exibição em andamento é cancelada (não vira comprovante), e a tela mostra
a peça institucional. Estado reportado: `OUT_OF_SCHEDULE`.

---

## 8. Atualização do player (OTA fase 1)

### 8.1 Manifesto

Vem dentro da resposta do heartbeat, em `update`:

```json
{
  "available": true,
  "required": false,
  "version": "1.1.0",
  "build": 3,
  "url": "https://cdn.mostrai.com.br/player/1.1.0.apk",
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "size": 8459231
}
```

Aceita também `disponivel`/`obrigatorio`/`versao`. Campos obrigatórios:
`build` (> 0), `url`, `sha256` (64 hex). **Manifesto sem hash válido é
ignorado** — instalar binário não verificado numa frota é o tipo de erro que
não se conserta remotamente depois.

### 8.2 Estados reportados no heartbeat

`NONE` · `AVAILABLE` · `DOWNLOADING` · `READY` · `INSTALL_REQUESTED` ·
`DEFERRED` · `FAILED`

### 8.3 O que o player faz

1. Ignora manifesto com `build` ≤ ao instalado. Se o build instalado alcançou
   o alvo, considera a atualização concluída e limpa o estado.
2. Baixa em segundo plano se `update.baixarAutomaticamente` não for `false`.
3. Verifica SHA-256 **antes** de promover o arquivo.
4. Confere que o APK tem o mesmo `applicationId` e `versionCode` maior.
5. Pede instalação via `PackageInstaller` **só entre itens**, nunca no meio
   de exibição paga.
6. Cancelamento → `DEFERRED` + janela de silêncio de
   `update.horasEntreTentativas` (padrão 6h) (auditoria, BUG-024).
7. Download que falhou (hash, pacote, rede) → `FAILED`, e o **mesmo** build
   só é baixado de novo depois de 6h. Um build novo passa direto — pode ser a
   correção (auditoria, BUG-011).
8. O manifesto repetido a cada heartbeat não é novidade: com o APK em disco,
   `READY`/`INSTALL_REQUESTED`/`DEFERRED` não rebaixam nada nem reabrem o
   diálogo. `DOWNLOADING` deixado por um processo que morreu é retomado, e um
   APK apagado pelo sistema (limpeza de `cacheDir`) é baixado de novo
   (auditoria, BUG-014/015).
9. `INSTALL_REQUESTED` sem resultado (TV desligada com o diálogo aberto) volta
   a ser oferecido depois de 6h, sem rebaixar.
10. Se nenhum diálogo cobrir a tela em 30s (sessão recusada), ou quando um
    diálogo com tema de diálogo é fechado, a exibição recomeça — o ciclo não
    fica parado esperando (auditoria, BUG-019).
11. Diálogo sem resposta: depois de ~5 min o watchdog traz o player de volta
    por cima dele, e a instalação é oferecida de novo mais tarde. É uma troca
    deliberada: sem ninguém na loja, a tela volta a exibir.

### 8.4 Limites do Android que o backend precisa conhecer

- **Sem Device Owner, a instalação sempre mostra diálogo do sistema.** Não há
  contorno. Alguém precisa apertar OK no controle.
- Exige `REQUEST_INSTALL_PACKAGES` (já no manifesto) **e** que "instalar apps
  desconhecidos" esteja autorizado para o pacote.
- **O APK precisa ser assinado com a mesma chave do instalado.** Assinatura
  diferente = o Android recusa. Ver seção 11.

---

## 9. Proof-of-play

### 9.1 O que mudou

| Antes | Agora |
|---|---|
| teto de 5.000 eventos | **50.000** |
| descarte pelo mais antigo | por valor: órfão → quarentena → comprovante |
| `400` descartava até 50 eventos | split binário isola o evento ruim |

A conta do teto: tela 24h com criativos de 10s gera 8.640 eventos/dia. O teto
anterior saturava em **13,9 h** — menos de um dia offline. O novo cobre ~5,8
dias.

### 9.2 `400`: o pedido concreto ao backend

**Use `200` com status individual em `resultados`, e reserve `400` para
envelope estruturalmente inválido** (JSON quebrado, campo `eventos`
ausente).

O caminho certo já está implementado dos dois lados: `resultados` já existe,
o player já o lê, e `item_invalido` já está na lista de status definitivos.
Basta o backend parar de usar `400` para problema de conteúdo.

O player agora se protege: ao receber `400` num lote com mais de um evento,
parte o lote ao meio e reenvia até isolar o culpado. Só ele vai para
quarentena; os irmãos saudáveis são entregues normalmente. Custo ~2·log₂(n)
requisições, uma vez.

Evento em quarentena sai da fila de envio, continua contado no diagnóstico,
e expira pelo horizonte de 7 dias.

### 9.2.1 Outras garantias do envio

- **`429`:** `Retry-After` é obedecido com teto de 30 min — um valor absurdo
  vindo de proxy ou CDN não empurra o lote para depois da expiração
  (auditoria, BUG-018).
- **`401`/`403`/`404`:** nada é descartado; o lote é reagendado com o mesmo
  backoff das falhas transitórias (5 s → 30 min), em vez de voltar ao
  servidor ao fim de cada exibição (auditoria, ROB-005).
- **`resultados` com elemento malformado:** só aquele elemento é ignorado; os
  outros status valem (auditoria, ROB-006).
- **Banco local indisponível** (disco cheio): o item toca **sem**
  comprovante, em vez de apagar a tela (auditoria, BUG-004).
- **"Eventos perdidos"** (painel) conta só comprovante de verdade — exibição
  terminada que não chegou ao servidor. Órfão (exibição interrompida) nunca
  conta; quarentena conta uma vez, ao entrar nela (auditoria, BUG-017).

### 9.3 Limiares sugeridos para o admin

| Condição | Nível |
|---|---|
| `fila.pendentes > 2.000` | atenção |
| `fila.pendentes > 10.000` | alerta |
| `fila.maisAntigoEm` > 48h | alerta, independente do volume |

O último é o mais informativo: volume alto com evento recente é uma tela
ocupada; evento de 48h é falha de envio, não de rede.

---

## 10. Estados derivados (o que o admin monta)

O player reporta **fato**. A classificação é do backend:

| Estado no admin | Como derivar |
|---|---|
| **Operando** | heartbeat recente **e** `estado` ∈ {`PLAYING`, `IDLE`} |
| **Fora do horário** | `estado = OUT_OF_SCHEDULE`, **ou** silêncio dentro de janela de fechamento conhecida |
| **Aguardando primeiro sinal** | tela cadastrada, nenhum heartbeat jamais recebido |
| **Sem sinal** | deveria estar operando e o último heartbeat passou da tolerância |
| **Erro do player** | `erro != null`, ou `estado` ∈ {`PLAYBACK_ERROR`, `DOWNLOAD_ERROR`, `AUTH_ERROR`, `CONFIG_ERROR`, `NO_PLAYLIST`} |
| **Em reparo / Inativa** | flag manual no admin; não gera alerta |

Tolerância sugerida para "Sem sinal": 3 ciclos (15 min). Dois ciclos
perdidos numa internet de comércio é rotina.

**O player nunca reporta "Sem sinal"** — por definição, uma tela sem rede não
consegue dizer nada. Esse estado é sempre inferido pela ausência.

---

## 11. Compatibilidade V1 ↔ V2

| Recurso | Backend V1 | Backend V2 |
|---|---|---|
| Playlist | funciona | funciona |
| Proof-of-play | funciona | funciona |
| Heartbeat | corpo V2 enviado; `404` ou corpo ignorado, tudo bem | completo |
| `margens` no heartbeat | continua aplicada | continua aplicada |
| `hello` | `404` → marca V1 | grava assinatura |
| `/config` | `404` → mantém local | aplica versionado |
| `contentHash` | ausente → fallback `criativoId` | endereçado por conteúdo |
| Provisionamento | JSON legado | token de uso único |
| OTA | sem manifesto → `NONE` | fase 1 |
| Horário | sem `operacao` → 24h | regime configurado |

**Nada quebra se o backend não for atualizado.** O player V2 rodando contra o
backend de hoje se comporta como o player de hoje, com os bugs R1–R11
corrigidos.

### Ordem sugerida do lado do backend

1. Aceitar e persistir o corpo do heartbeat V2 (só observar). Já habilita
   **Operando / Sem sinal / Erro do player** no admin.
2. `POST /hello` + colunas de aparelho.
3. `configVersion` + `GET /config` + horário operacional.
4. Parar de usar `400` para conteúdo.
5. `contentHash` na playlist.
6. `POST /player/provisionar` + token.
7. Rotação de credencial.
8. Manifesto de update.

Os passos 1–2 não quebram nada e já entregam a ficha da tela.

---

## 12. Referências no código

| Assunto | Arquivo |
|---|---|
| Headers, classificação HTTP, rotação | `network/MostraiApi.kt` |
| Famílias de falha | `network/ResultadoHttp.kt` |
| Heartbeat V2 | `network/HeartbeatJson.kt` |
| Hello | `network/HelloJson.kt` |
| Degradação V1/V2 | `network/SincronizacaoV2.kt` |
| Config remota | `config/ConfigRemota.kt` |
| Horário | `config/HorarioOperacional.kt` |
| Erro durável | `estado/DiarioBordo.kt` |
| Estados | `estado/EstadoPlayer.kt` |
| Cache por hash | `cache/ChaveCache.kt`, `cache/CacheMidia.kt` |
| Fila, quarentena, split | `proof/FilaProofOfPlay.kt`, `proof/ProofOfPlayDb.kt` |
| OTA | `update/Atualizador.kt`, `update/UpdateManifesto.kt` |
| Kiosk | `kiosk/Kiosk.kt` (Device Owner: preparado, mas sem `DeviceAdminReceiver` declarado — não habilitável ainda), `kiosk/Watchdog.kt`, `BootReceiver.kt` |
