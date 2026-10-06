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
3. **A chave nasce uma vez, num ambiente persistente sob controle do
   dono** (a máquina dele, ou uma sessão que ele autorize e de onde possa
   recuperar o arquivo). Sessão efêmera (contêiner que é descartado) nunca
   gera a chave definitiva — foi o caso da rodada final de 05/10/2026, que
   parou na assinatura por isso. A chave vive fora do repositório, com
   cópia offline. `keystore.properties` e `*.jks` estão no `.gitignore`, e
   `scripts/varrer-segredos.sh` (no CI) falha se algum deles entrar no Git
   — inclusive no histórico. Só a impressão digital pública do certificado
   é versionada (`scripts/certificado-producao.sha256`).
4. **`versionCode` sempre sobe, e nenhum número volta.** O Android só
   instala por cima de uma versão com `versionCode` menor.

   | versionCode | versionName | O que é |
   |---|---|---|
   | 4 | 3.0.0 | base de produção (sem atualizador) |
   | 5 | 3.0.0-teste-n5 | teste N+1 do 3.0.0 — NAO-DISTRIBUIR, queimado |
   | 6 | 3.0.1 | atualização oficial por pendrive — o candidato |
   | 7 | 3.0.1-teste-n7 | teste do modal do pendrive — NAO-DISTRIBUIR, queimado |
   | 8+ | — | próxima versão oficial |
5. **Produção começa do zero no 3.0.0** (decisão do dono, 05/10/2026): as
   TVs de teste são apagadas e reinstaladas; não há migração de credencial,
   cache, fila ou SQLite de instalações debug/2.0.0. O primeiro
   provisionamento oficial já é com o release assinado.

## Primeira assinatura (uma vez só)

> **Feita em 05/10/2026.** A chave definitiva foi gerada por autorização
> explícita do dono numa sessão efêmera e entregue a ele para download; a
> impressão digital pública está em `scripts/certificado-producao.sha256`
> (`8c4ea2cc33201dd3410bb79ac96965e2f4e8a77cc47faf7d9fcd1b841ea6cbed`). Os passos abaixo ficam como
> referência — **não gerar outra chave**: uma segunda chave quebraria a
> atualização da frota.

```sh
# 1. Gerar a chave definitiva FORA do repositório (pede as senhas; anotar
#    em gerenciador de senhas, nunca em arquivo do projeto)
keytool -genkeypair -v -keystore ~/mostrai-chaves/mostrai-release.jks \
  -alias mostrai -keyalg RSA -keysize 4096 -validity 10000

# 2. keystore.properties na raiz do repositório (já no .gitignore)
#    storeFile=/caminho/absoluto/mostrai-release.jks
#    storePassword=…   keyAlias=mostrai   keyPassword=…

# 3. Primeiro candidato: o registro mostra a impressão digital e pede para
#    registrá-la
scripts/release-candidato.sh

# 4. Gravar a impressão digital pública e commitar (só ela)
echo "<sha-256 do certificado, do REGISTRO.txt>" > scripts/certificado-producao.sha256
git add scripts/certificado-producao.sha256 && git commit -m "Release: impressão digital da chave definitiva"

# 5. Candidato oficial, agora conferido contra a impressão registrada
#    (numa cópia limpa do commit — o registro marca árvore suja)
scripts/release-candidato.sh
```

Fazer **duas cópias** do `.jks` (uma offline) e guardar as senhas
separadas do arquivo antes de instalar em qualquer TV.

## Gerar o candidato

Na máquina do dono, com `keystore.properties` na raiz:

```sh
scripts/release-candidato.sh
```

O script:

1. roda a varredura de segredos;
2. `./gradlew assembleRelease` (sem `clean`; para cliente, rodar numa cópia limpa do commit);
3. confere o APK (`scripts/verificar-apk.sh --release`): pacote, `minSdk 26`,
   `targetSdk 36`, `compileSdk 36`, exatamente as 7 permissões decididas
   (`docs/permissoes-especiais.md`; + a de assinatura que o androidx.core
   declara para o próprio app), `READ_EXTERNAL_STORAGE` só até o 29, sem
   "todos os arquivos", sem `CATEGORY_HOME`, sem `debuggable`, sem texto
   puro, `networkSecurityConfig` e `dataExtractionRules` presentes;
4. confere a assinatura (`apksigner verify`) e **falha se for a chave de
   depuração**, se houver mais de um assinante, ou se a impressão digital
   SHA-256 do certificado não for a registrada em
   `scripts/certificado-producao.sha256` (versionado — a impressão é
   pública, identifica a chave sem dar acesso a ela). Desde 05/10/2026 o
   arquivo é obrigatório: o build o embute no APK (o updater por pendrive
   confere contra ele) e falha sem ele;
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

## Pacote de pendrive (3.0.1 em diante)

Toda versão oficial a partir da 3.0.1 vai a campo como pacote de pendrive
(`docs/atualizacao-usb.md`):

```sh
scripts/preparar-usb-update.sh app/build/release-candidato/Mostrai-Player-<versão>-release.apk
# → app/build/usb/Mostrai-USB-<versão>/MOSTRAI/update/{Mostrai-Player.apk, update.json}
# → app/build/usb/Mostrai-USB-<versão>.zip (+ LEIA-ME.txt)
```

O script recusa APK que não passe em `verificar-apk.sh --release`, com
assinatura inválida ou com impressão digital diferente de
`scripts/certificado-producao.sha256`, e APK de teste sem `--teste`. Guardar
o `.zip` e o SHA-256 que ele imprime junto do `REGISTRO.txt`.

## Teste N → N+1 com a mesma chave (obrigatório antes do primeiro cliente)

Prova que a frota consegue receber a próxima versão sem desinstalar.
Itens 34–37 e 46–61 do `docs/checklist-fisico-producao.md`.

1. **3.0.0 → 3.0.1, à mão** (o 3.0.0 não tem atualizador): TCL no 3.0.0
   oficial (`versionCode 4`), provisionada, tocando, com comprovante
   pendente. Instalar o 3.0.1 oficial (`versionCode 6`) por cima pelo
   gerenciador de arquivos, **sem desinstalar**. Esperado: instala; o
   Player volta sozinho sem pedir ID/código; comprovantes pendentes
   seguem e são enviados; bloco técnico `3.0.1+6`.
2. Instalar de volta o 3.0.0: o Android **recusa** (downgrade). Instalar um
   APK de outra chave (o debug): **recusa**.
3. **3.0.1 → N+1 pelo pendrive** (prova o atualizador): gerar o N+1 de
   teste **da mesma árvore e com a mesma chave**, sem editar nada:

   ```sh
   scripts/release-teste-n-mais-1.sh 7
   scripts/preparar-usb-update.sh app/build/release-teste-n1/Mostrai-Player-3.0.1-teste-n7-NAO-DISTRIBUIR.apk --teste
   # → app/build/usb/Mostrai-USB-3.0.1-teste-n7-NAO-DISTRIBUIR.zip
   ```

   Pendrive com a pasta `MOSTRAI` → modal → Atualizar agora → Instalar.
   Esperado: o Player volta sozinho em `3.0.1-teste-n7+7`, mesmo cadastro.
4. Descartar o teste: a TV no 7 só volta ao 3.0.1 oficial **desinstalando**
   e provisionando de novo. Por isso a próxima oficial é `versionCode 8`+.

O `versionCode 5` (teste N+1 do 3.0.0, gerado em 05/10/2026) não é mais
necessário — o 3.0.1 oficial cumpre esse papel — e nunca é reutilizado.

## O que o release **não** tem

- OTA pela rede. Desde a 3.0.1 o Player se atualiza **por pendrive**,
  com confirmação da pessoa no instalador do Android
  (`docs/atualizacao-usb.md`); o pacote `atualizacao/` não fala com a rede
  (`GuardaMvpTest`).
- Logs com chave, token, código de instalação ou cabeçalho (testes
  `SegredoForaTest` e `ProvisionamentoCicloTest`).
- Provisionamento fora da TV (JSON, pendrive, `adb`, `BuildConfig`).
- `CATEGORY_HOME`, Device Owner, serviço em primeiro plano, câmera,
  microfone.
