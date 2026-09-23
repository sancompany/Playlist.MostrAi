# Checklist do backend/admin da Mostraí — Player V2

Para a sessão que mantém `sancompany/mostrai` (backend, admin, Rede, Tela,
Player API). O contrato exato está em `docs/player-v2-contract.md`; aqui é a
lista de trabalho.

**Nada disto é bloqueante para lançar o primeiro ponto.** O player V2 já roda
contra o backend de hoje. Cada item abaixo liga uma capacidade nova.

---

## Ordem recomendada

Os passos 1 e 2 não quebram nada, não exigem mudança no player, e já entregam
a ficha da tela no admin. O resto pode vir depois, na ordem que fizer sentido.

---

## 1. Receber o heartbeat V2 (só observar)

**Entrega: Operando / Sem sinal / Erro do player no admin.**

`POST /player/:dispositivoId/heartbeat` passa a receber corpo JSON. Hoje o
player manda corpo vazio; o V2 manda o payload da seção 4.1 do contrato.

### Migration — colunas em `screens` (ou tabela `player_status`)

| Coluna | Tipo | Origem |
|---|---|---|
| `last_seen_at` | timestamptz | momento do heartbeat |
| `player_state` | text | `estado` |
| `contract_version` | int | header `X-Player-Contract` |
| `player_version` | text | header `X-Player-Version` |
| `applied_config_version` | int | `configVersionAplicada` |
| `current_creative_id` | text null | `criativoId` |
| `last_playlist_ok_at` | timestamptz null | `ultimaPlaylistOkEm` |
| `queue_depth` | int | `fila.pendentes` |
| `queue_oldest_at` | timestamptz null | `fila.maisAntigoEm` |
| `last_error_code` | text null | `erro.codigo` |
| `last_error_at` | timestamptz null | `erro.ocorreuEm` |
| `last_error_message` | text null | `erro.mensagem` |
| `clock_drift_ms` | bigint null | `desvioRelogioMs` |
| `update_state` | text null | `update.estado` |

Corpo vazio (player V1) continua válido: atualize só `last_seen_at`.

`erro: null` é **informação**, não ausência — significa "o player diz que
está sem erro". Limpe `last_error_*` quando vier `null`.

Dois limites do lado do player (contrato §4.1): o erro é limpo quando a
playlist volta a ser buscada com sucesso — inclusive logo no boot — e crash
do app não é registrado. Um erro pontual pode aparecer só por alguns
minutos; se quiser histórico, **guarde** os `last_error_*` recebidos em vez
de só sobrescrever.

### Estados derivados

Ver seção 10 do contrato. Tolerância sugerida para "Sem sinal": 3 ciclos
(15 min).

---

## 2. `POST /player/:dispositivoId/hello`

Dados técnicos, enviados uma vez por mudança.

| Coluna | Tipo |
|---|---|
| `device_manufacturer` | text |
| `device_model` | text |
| `android_version` | text |
| `screen_width` / `screen_height` | int |
| `timezone` | text |
| `first_hello_at` | timestamptz |

Responder `{"configVersion": <n>}` poupa um ciclo de heartbeat. Responder
`{}` também é válido.

---

## 3. Configuração remota versionada

### `desired_config_version` × `applied_config_version`

- `desired_config_version` incrementa a cada alteração da tela no admin.
- O heartbeat devolve `configVersion: <desired>`.
- O player busca `/config`, aplica, e passa a reportar
  `configVersionAplicada`.
- Admin mostra "Configuração pendente" quando `desired != applied`.

> **Armadilha de UX.** Com heartbeat de 5 minutos, toda alteração vai marcar
> "pendente" por até 5 minutos, sempre. Só mostre pendência depois de ~2
> ciclos, senão o indicador vira ruído.

### `GET /player/:dispositivoId/config`

Formato na seção 5 do contrato. `configVersion` é o único campo obrigatório.

**Campo ausente significa "não mexa", nunca "zere".** É o que permite
evoluir a config sem coordenar release do player.

### Campos de operação em `screens`

| Coluna | Observação |
|---|---|
| `operation_regime` | `FOLLOW_POINT` / `24_HOURS` / `CUSTOM` |
| `timezone` | IANA, ex. `America/Sao_Paulo` |
| `schedule` | jsonb, faixas por dia da semana |
| `holidays` | jsonb, data → faixas (lista vazia = fechado) |
| `min_build` | `versionCode` mínimo — o player lê, mas hoje **não age** sobre ele |
| `admin_pin` | 4 dígitos |

`FOLLOW_POINT` deve resolver as faixas do ponto no servidor e entregá-las
já materializadas — o player não conhece o conceito de "ponto".

Formato das faixas (contrato §7): `HH:MM` ou `HH:MM:SS`. Uma faixa que cruza
a meia-noite pertence ao dia em que **começa** (`"sex": 22:00–02:00` vale até
sábado 02:00). Para fechar um dia, mande a lista vazia explícita; faixa que
o player não consegue ler deixa o dia **aceso**. Dia inteiro é
`00:00`–`24:00` — `00:00`–`00:00` é uma faixa vazia e fecha o dia.

### Onde ficam as margens

Hoje vêm no heartbeat (migration 069) e continuam funcionando. Recomendação:
mover para `/config` e manter nos dois lugares durante a transição, para não
ficarem duas fontes de verdade em definitivo.

---

## 4. Parar de usar `400` no proof-of-play

**O mais barato da lista, e o que mais protege receita.**

Hoje um `400` fazia o player descartar até 50 comprovantes de uma vez. O
player já se protege com split binário, mas a correção certa é do lado do
servidor:

- `200` com status individual por evento em `resultados` — mecanismo que **já
  existe** dos dois lados.
- `400` **só** para envelope estruturalmente inválido (JSON quebrado, campo
  `eventos` ausente).
- Evento individual ruim → `200` com `status: "item_invalido"`, que já está na
  lista de status definitivos do player.

---

## 5. `contentHash` na playlist

Campo opcional por item: SHA-256 do arquivo, hexadecimal, 64 caracteres.

- Calcular no upload do criativo e persistir junto.
- Entregar em `GET /playlist/:dispositivoId`.
- Player sem o campo continua no comportamento antigo.
- Servir a mídia em `https` direto, de preferência com `Content-Type`
  `video/*` (no upload para o Storage, informe o tipo — sem ele o padrão é
  `text/plain`, aceito pelo player, mas enganoso). Resposta HTML/JSON nunca
  vira cache, e redirecionamento `http → https` não é seguido pelo player.

**O que isso resolve:** hoje o cache usa `criativoId` como identidade física e
depende da promessa de que `criativoId → url` é imutável. Se a promessa for
quebrada, a tela serve o arquivo antigo indefinidamente — sem TTL, sem
revalidação. Com hash, o player verifica o download e um arquivo novo
naturalmente tem outra chave.

| Coluna em `creatives` | Tipo |
|---|---|
| `content_sha256` | text (64 hex) |
| `content_size` | bigint |

---

## 6. Provisionamento por token

### `POST /player/provisionar`

Requisição: `{"tokenProvisionamento": "tok_..."}`
Resposta: `{"dispositivoId": "...", "chaveAparelho": "..."}`

### Tabela `provisioning_tokens`

| Coluna | Observação |
|---|---|
| `token` | único, aleatório, imprevisível |
| `screen_id` | tela a que dá acesso |
| `expires_at` | validade curta (dias, não meses) |
| `used_at` | **uso único**: null até a troca |
| `created_by` | auditoria |

O admin gera o token ao criar a Tela e oferece o `mostrai-config.json` para
download.

**O ganho:** o pendrive deixa de carregar o segredo definitivo. Um pendrive
perdido expõe um token já queimado, não a credencial permanente de uma tela
em operação — e o mesmo pendrive não provisiona duas TVs por engano.

O formato legado continua suportado pelo player; não há pressa.

**Pedido:** aceite o mesmo token de novo por alguns minutos depois da troca,
devolvendo as mesmas credenciais. Se a resposta se perder na rede depois de
o servidor queimar o token, o aparelho não tem outro jeito de se recuperar
sem visita.

---

## 7. Rotação de credencial

- `novaChave` na resposta do heartbeat.
- Aceitar **as duas chaves** por uma janela (24h basta).
- Aposentar a antiga no primeiro uso confirmado da nova.
- Rejeitar credencial vazia explicitamente.

O player só promove a chave nova depois de uma resposta bem-sucedida de uma
requisição **que levou** a chave nova; em `401` dessa requisição volta para a
antiga. Respostas de requisições que saíram com a chave antiga não decidem
nada. Sem a janela de sobreposição do lado do
servidor, uma tela que perca a rede no meio da troca fica sem credencial.

| Coluna | Observação |
|---|---|
| `device_key` | atual |
| `device_key_previous` | aceita até `device_key_retire_at` |
| `device_key_retire_at` | timestamptz |

O header novo é `X-Aparelho-Key`; `X-Aparelho-Id` continua vindo com o mesmo
valor. O backend pode migrar quando quiser.

---

## 8. Manifesto de atualização

`update` na resposta do heartbeat, formato na seção 8.1 do contrato.

| Coluna em `player_releases` | Observação |
|---|---|
| `version_name` / `version_code` | |
| `apk_url` | |
| `sha256` | **obrigatório** — sem ele o player ignora o manifesto |
| `size_bytes` | |
| `required` | obrigatória vs. recomendada |
| `rollout_percent` | opcional, para liberar aos poucos |
| `min_build_to_upgrade` | opcional |

Admin precisa mostrar `update_state` por tela (vem no heartbeat) e permitir
segurar/liberar uma versão.

Comportamento do player a considerar na UI: um build cujo download falhou
fica `FAILED` e só é tentado de novo depois de 6h (publicar um build novo
passa direto); a janela após o operador cancelar vem de
`update.horasEntreTentativas` na config; um diálogo sem resposta é coberto
pelo player depois de ~5 min e reoferecido depois.

**Limite do Android:** sem Device Owner a instalação sempre pede confirmação
no controle. O backend não muda isso; o que muda é que ninguém precisa mais
levar pendrive até a loja.

---

## 9. UI da ficha da tela

Blocos sugeridos:

**Cabeçalho** — estado derivado (badge), último heartbeat, versão do player,
contrato.

**Configuração** — versão desejada × aplicada, badge de pendência (após 2
ciclos), regime operacional e fuso, margens e rotação.

**Operação** — última playlist OK, criativo no ar, fila pendente com
limiares (2.000 atenção / 10.000 alerta), evento mais antigo (alerta acima de
48h), desvio de relógio.

**Erro** — código, momento, mensagem. Só aparece quando há.

**Atualização** — estado, versão alvo, obrigatória ou não.

**Aparelho** — fabricante, modelo, Android, resolução, primeiro hello.

---

## 10. Compatibilidade com players V1

Durante a transição vão existir telas nas duas versões.

- Distinguir pelo header `X-Player-Contract` (ausente = V1).
- Heartbeat com corpo vazio continua válido.
- Rotas V2 podem responder `404` para tudo — o player V1 nunca as chama, e o
  V2 degrada sozinho.
- Playlist sem `contentHash` continua válida para os dois.

Nenhuma mudança no backend exige atualizar os players em campo primeiro.

---

## 11. O que o player NÃO pede

Para não gerar trabalho desnecessário:

- **URL assinada de mídia** — avaliado e descartado por ora. Mais um modo de
  falha numa internet de comércio do que proteção real, para conteúdo que é
  criativo publicitário. Recomendação: URL pública com caminho não
  enumerável (UUID). Com `contentHash`, o cache fica imune à troca de URL.
- **Analytics de uso** — vetado em `CONSTRAINTS.md`. O heartbeat carrega
  estado operacional, não comportamento.
- **Serviço de terceiro para captura de erro** — vetado. O erro durável é do
  próprio backend da Mostraí.
- **QR code / código digitado no provisionamento** — pendrive já está na mão
  para instalar o APK.
- **Device Owner como requisito** — há relato documentado de falha em TV
  Android da TCL, fabricante do parque instalado. O player detecta e usa se
  houver; nunca exige.

---

## 12. Nota sobre o veto de telemetria

`CONSTRAINTS.md` veta "telemetria rica além do proof-of-play", e vale
registrar por que o heartbeat V2 não o viola: o veto é sobre **serviço de
terceiro** (Sentry) e **analytics de uso**. O que o heartbeat carrega é
estado operacional do próprio parque, indo para o próprio backend da
Mostraí, sem dado pessoal e sem comportamento de usuário — é diagnóstico de
infraestrutura, a mesma categoria do proof-of-play que o veto explicitamente
preserva.

Vale deixar isso escrito também do lado de lá, senão daqui a três meses
alguém lê o veto e conclui que foi atropelado.
