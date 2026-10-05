#!/usr/bin/env bash
# Gera o candidato a release do Mostraí Player e o registro dele:
#   app/build/release-candidato/Mostrai-Player-<versão>-release.apk
#   app/build/release-candidato/REGISTRO.txt
# O registro tem SHA-256, commit, níveis de SDK, versionCode e a impressão
# digital do certificado. Sem keystore.properties (fora do Git, RUNBOOK.md)
# o APK sai SEM assinatura e o registro diz "NÃO É PRODUÇÃO" — nunca
# instalar esse APK numa TV de cliente. Nunca imprime senha nem chave.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "ANDROID_HOME não definido" >&2; exit 2; }
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"

scripts/varrer-segredos.sh
# Sem `clean`: o CI confere o APK debug no mesmo build (e o Gradle refaz o
# que mudou). Para um registro de cliente, rodar numa cópia limpa do commit.
./gradlew --no-daemon -q assembleRelease

saida=app/build/outputs/apk/release
if [ -f "$saida/app-release.apk" ]; then
  origem="$saida/app-release.apk"; assinado=1
else
  origem="$saida/app-release-unsigned.apk"; assinado=0
fi
[ -f "$origem" ] || { echo "APK de release não encontrado em $saida" >&2; exit 1; }

verificacao="$(scripts/verificar-apk.sh "$origem" --release)"
versao="$(sed -n 's/.*versionName=\([^ ]*\).*/\1/p' <<<"$verificacao" | head -1)"
vcode="$(sed -n 's/.*versionCode=\([^ ]*\).*/\1/p' <<<"$verificacao" | head -1)"

destino=app/build/release-candidato
rm -rf "$destino"; mkdir -p "$destino"
if [ "$assinado" = 1 ]; then
  nome="Mostrai-Player-$versao-release.apk"
else
  nome="Mostrai-Player-$versao-release-NAO-ASSINADO.apk"
fi
cp "$origem" "$destino/$nome"

if [ "$assinado" = 1 ]; then
  "$bt/apksigner" verify --min-sdk-version 26 "$destino/$nome"
  certificado="$("$bt/apksigner" verify --print-certs "$destino/$nome" | grep -E 'certificate (SHA-256|DN)' )"
  esquemas="$("$bt/apksigner" verify --verbose "$destino/$nome" | grep -E 'Verified using v[0-9]' )"
  if grep -qi "CN=Android Debug" <<<"$certificado"; then
    echo "FALHOU: release assinado com a chave de DEPURAÇÃO" >&2; exit 1
  fi
  # "A mesma chave para sempre": a impressão digital da chave definitiva
  # fica versionada (é pública — identifica, não assina). Outra chave aqui
  # quebraria a atualização da frota inteira.
  impressao="$(sed -n 's/.*certificate SHA-256 digest: *//p' <<<"$certificado" | head -1 | tr 'A-F' 'a-f')"
  registrada_arq=scripts/certificado-producao.sha256
  if [ -f "$registrada_arq" ]; then
    registrada="$(tr -d ' \n' < "$registrada_arq" | tr 'A-F' 'a-f')"
    if [ "$impressao" != "$registrada" ]; then
      echo "FALHOU: assinado com OUTRA chave ($impressao), não a definitiva ($registrada)" >&2; exit 1
    fi
    situacao="CANDIDATO A RELEASE — assinado com a chave definitiva registrada"
  else
    situacao="CANDIDATO A RELEASE — PRIMEIRA ASSINATURA: gravar '$impressao' em $registrada_arq e commitar antes de instalar em cliente"
  fi
else
  certificado="(sem assinatura)"
  esquemas="(sem assinatura)"
  situacao="NÃO É PRODUÇÃO — APK sem assinatura (falta keystore.properties; ver RUNBOOK.md)"
fi

{
  echo "Mostraí Player — registro do candidato a release"
  echo "situacao: $situacao"
  echo "arquivo: $nome"
  echo "sha256: $(sha256sum "$destino/$nome" | cut -d' ' -f1)"
  echo "tamanho_bytes: $(stat -c %s "$destino/$nome")"
  echo "versionName: $versao"
  echo "versionCode: $vcode"
  echo "commit: $(git rev-parse HEAD)$([ -z "$(git status --porcelain)" ] || echo ' (ÁRVORE COM ALTERAÇÕES NÃO COMMITADAS OU ARQUIVOS FORA DO GIT — NÃO USAR EM CLIENTE)')"
  echo "gerado_em_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "build_tools: $(basename "$bt")"
  echo "$verificacao" | sed 's/^/verificacao: /'
  echo "$certificado" | sed 's/^/certificado: /'
  echo "$esquemas" | sed 's/^/assinatura: /'
} > "$destino/REGISTRO.txt"

cat "$destino/REGISTRO.txt"
