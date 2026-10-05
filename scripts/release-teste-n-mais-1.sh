#!/usr/bin/env bash
# Gera o APK N+1 do teste "atualização com a mesma chave" (docs/release-producao.md):
# mesmo código, versionCode maior só na linha de comando, assinado com a
# MESMA chave definitiva. Nunca vai a cliente e nunca é commitado.
#   app/build/release-teste-n1/Mostrai-Player-<versão>-NAO-DISTRIBUIR.apk
# Uso: scripts/release-teste-n-mais-1.sh [versionCode]   (padrão: 5)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

codigo="${1:-5}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "ANDROID_HOME não definido" >&2; exit 2; }
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"
[ -f keystore.properties ] || { echo "FALHOU: sem keystore.properties — o N+1 só vale assinado com a chave definitiva" >&2; exit 1; }
[ -f scripts/certificado-producao.sha256 ] || { echo "FALHOU: registre antes a impressão digital do N (scripts/certificado-producao.sha256)" >&2; exit 1; }

./gradlew --no-daemon -q assembleRelease "-Pmostrai.versionCodeTeste=$codigo"
origem=app/build/outputs/apk/release/app-release.apk
[ -f "$origem" ] || { echo "FALHOU: APK assinado não encontrado" >&2; exit 1; }
scripts/verificar-apk.sh "$origem" --release >/dev/null

impressao="$("$bt/apksigner" verify --print-certs "$origem" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | tr 'A-F' 'a-f')"
registrada="$(tr -d ' \n' < scripts/certificado-producao.sha256 | tr 'A-F' 'a-f')"
[ "$impressao" = "$registrada" ] || { echo "FALHOU: N+1 assinado com outra chave" >&2; exit 1; }

versao="$("$bt/aapt2" dump badging "$origem" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")"
destino=app/build/release-teste-n1
rm -rf "$destino"; mkdir -p "$destino"
cp "$origem" "$destino/Mostrai-Player-$versao-NAO-DISTRIBUIR.apk"
# O próximo build oficial não pode reaproveitar este APK.
rm -f "$origem"
echo "N+1 de teste: $destino/Mostrai-Player-$versao-NAO-DISTRIBUIR.apk (versionCode $codigo, mesma chave)"
echo "sha256: $(sha256sum "$destino/Mostrai-Player-$versao-NAO-DISTRIBUIR.apk" | cut -d' ' -f1)"
