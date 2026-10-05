# Release de produção — Mostraí Player

Como sai um APK que pode ir para TV de cliente, e como se prova que é ele.
Passo a passo de chave e build em `RUNBOOK.md` ("Chave de assinatura").

## Regras

1. **Só o APK de release assinado com a chave definitiva vai para TV de
   cliente.** O debug (`app-debug.apk`) é para bancada: aceita HTTP em
   `127.0.0.1` e é assinado com a chave de depuração, que nenhuma
   atualização futura conseguiria substituir.
2. **A mesma chave para sempre.** O Android recusa atualizar um app
   assinado com outra chave. Perder a chave = desinstalar e reprovisionar a
   frota inteira, TV por TV.
3. **Nenhuma sessão automatizada gera, guarda ou vê a chave.** Ela vive na
   máquina do dono (e em cópia offline). `keystore.properties` e `*.jks`
   estão no `.gitignore`, e `scripts/varrer-segredos.sh` (no CI) falha se
   algum deles entrar no Git — inclusive no histórico.
4. **`versionCode` sempre sobe.** O Android só instala por cima de uma
   versão com `versionCode` menor. 3.0.0 = 4.
5. **Produção começa do zero no 3.0.0** (decisão do dono, 05/10/2026): as
   TVs de teste são apagadas e reinstaladas; não há migração de credencial,
   cache, fila ou SQLite de instalações debug/2.0.0. O primeiro
   provisionamento oficial já é com o release assinado.

## Gerar o candidato

Na máquina do dono, com `keystore.properties` na raiz:

```sh
scripts/release-candidato.sh
```

O script:

1. roda a varredura de segredos;
2. `./gradlew clean assembleRelease`;
3. confere o APK (`scripts/verificar-apk.sh --release`): pacote, `minSdk 26`,
   `targetSdk 36`, `compileSdk 36`, exatamente as 4 permissões decididas (+ a
   de assinatura que o androidx.core declara para o próprio app), sem
   `CATEGORY_HOME`, sem `debuggable`, sem texto puro,
   `networkSecurityConfig` e `dataExtractionRules` presentes;
4. confere a assinatura (`apksigner verify`) e **falha se for a chave de
   depuração** ou se a impressão digital SHA-256 do certificado não for a
   registrada em `scripts/certificado-producao.sha256` (versionado — a
   impressão é pública, identifica a chave sem dar acesso a ela). Na
   primeira assinatura o arquivo ainda não existe: o registro pede para
   gravá-lo e commitá-lo antes de instalar em cliente;
5. copia para `app/build/release-candidato/Mostrai-Player-<versão>-release.apk`
   e escreve `REGISTRO.txt` ao lado.

Sem `keystore.properties` (o caso do CI) o APK sai como
`Mostrai-Player-<versão>-release-NAO-ASSINADO.apk` e o registro diz
**"NÃO É PRODUÇÃO"**. O CI publica esse par como artefato só para
conferência de build — nunca para instalar.

## Registro de release

Para cada release que vai a campo, guardar (fora do Git, junto da chave ou
na pasta de entregas do Mostraí) o `REGISTRO.txt` do candidato:

| Campo | De onde vem |
|---|---|
| `arquivo`, `sha256`, `tamanho_bytes` | o APK copiado |
| `versionName`, `versionCode` | `aapt2 dump badging` |
| `minSdk`, `targetSdk`, `compileSdk`, permissões | `verificar-apk.sh` |
| `commit` | `git rev-parse HEAD` (marca se a árvore tinha alteração não commitada — **não** usar esse APK) |
| `certificado` (DN e SHA-256) | `apksigner verify --print-certs` |
| `assinatura` (esquemas v1/v2/v3) | `apksigner verify --verbose` |

A impressão digital SHA-256 do certificado tem que ser **a mesma** em todos
os registros, do 3.0.0 em diante — o script recusa outra. O `commit` do
registro só vale se não vier marcado "ÁRVORE COM ALTERAÇÕES NÃO COMMITADAS
OU ARQUIVOS FORA DO GIT": para um APK de cliente, gerar numa cópia limpa do
commit.

## Teste N → N+1 com a mesma chave (obrigatório antes do primeiro cliente)

Prova que a frota consegue receber a próxima versão sem desinstalar.

1. Gerar o candidato 3.0.0 (`versionCode 4`) e instalar numa TCL de teste;
   provisionar; deixar tocar e gerar comprovantes.
2. Gerar um candidato N+1 **da mesma árvore**, só com `versionCode 5` e
   `versionName "3.0.1-teste"` (alteração local, não commitada), com a
   mesma `keystore.properties`.
3. Instalar o N+1 por cima pelo pendrive, **sem desinstalar**.
4. Esperado: instala sem "app não instalado"; a TV volta a tocar sem pedir
   ID/código; comprovantes e segmentos pendentes continuam e são enviados;
   o bloco técnico mostra `3.0.1-teste+5`.
5. Instalar de volta um APK com `versionCode` **menor** (o 3.0.0): o
   Android deve **recusar** (downgrade). Registrar.
6. Instalar um APK com a mesma versão mas assinado com **outra** chave (o
   debug serve): o Android deve **recusar**. Registrar.
7. Descartar o N+1 de teste (não vai a campo) e reinstalar o 3.0.0
   oficial do zero antes do provisionamento oficial.

Itens 34–38 do `docs/checklist-fisico-producao.md`.

## O que o release **não** tem

- OTA (atualização é pendrive, por pessoa).
- Logs com chave, token, código de instalação ou cabeçalho (testes
  `SegredoForaTest` e `ProvisionamentoCicloTest`).
- Provisionamento fora da TV (JSON, pendrive, `adb`, `BuildConfig`).
- `CATEGORY_HOME`, Device Owner, serviço em primeiro plano, câmera,
  microfone.
