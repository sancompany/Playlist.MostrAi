#!/usr/bin/env bash
# Confere um APK do Mostraí Player contra as regras de produção — o que o
# CI e o registro de release (docs/release-producao.md) exigem:
#   pacote, versionCode/versionName, minSdk 26, targetSdk 36, sem
#   debuggable, sem tráfego em texto puro, exatamente as 4 permissões
#   decididas (+ a de assinatura do androidx.core), sem CATEGORY_HOME (instalador da TCL recusa).
# Uso: scripts/verificar-apk.sh caminho/do.apk [--release]
# Sai com erro na primeira violação. Não imprime nada secreto.
set -euo pipefail

apk="${1:?uso: $0 caminho/do.apk [--release]}"
modo="${2:-}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "ANDROID_HOME não definido" >&2; exit 2; }
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"
aapt2="$bt/aapt2"

falha() { echo "FALHOU: $*" >&2; exit 1; }

badging="$("$aapt2" dump badging "$apk")"
manifesto="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"

pacote="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
vcode="$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
vname="$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
minsdk="$(sed -n "s/^minSdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging")"
targetsdk="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging")"
compilesdk="$(sed -n "s/^package: .*compileSdkVersion='\([^']*\)'.*/\1/p" <<<"$badging")"

[ "$pacote" = "br.com.mostrai.player" ] || falha "pacote '$pacote'"
[ "$minsdk" = "26" ] || falha "minSdk $minsdk (esperado 26 — TCL 32S6500S, Android 8)"
[ "$targetsdk" = "36" ] || falha "targetSdk $targetsdk (esperado 36 — ADR 0001)"
[ "$compilesdk" = "36" ] || falha "compileSdk $compilesdk (esperado 36)"

permissoes="$(sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" <<<"$badging" | sort | tr '\n' ' ')"
# As 4 decididas (ADR 0001) + a de assinatura que o androidx.core declara
# para os próprios receivers (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, do
# próprio app — não dá acesso a nada de fora).
# 3.0.1: + REQUEST_INSTALL_PACKAGES e READ_EXTERNAL_STORAGE (atualização por
# pendrive) e WAKE_LOCK (teste de ligar a tela) — docs/permissoes-especiais.md.
esperadas="$(printf '%s\n' "$pacote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android.permission.ACCESS_NETWORK_STATE \
  android.permission.INTERNET android.permission.RECEIVE_BOOT_COMPLETED android.permission.SYSTEM_ALERT_WINDOW \
  android.permission.REQUEST_INSTALL_PACKAGES android.permission.READ_EXTERNAL_STORAGE android.permission.WAKE_LOCK \
  | sort | tr '\n' ' ')"
[ "$permissoes" = "$esperadas" ] || falha "permissões: $permissoes"
grep -q "uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='29'" <<<"$badging" \
  || falha "READ_EXTERNAL_STORAGE sem maxSdkVersion 29"
grep -q "MANAGE_EXTERNAL_STORAGE" <<<"$badging" && falha "MANAGE_EXTERNAL_STORAGE declarada"

grep -q "android.intent.category.HOME" <<<"$manifesto" && falha "CATEGORY_HOME no manifesto"
grep -q "android.intent.category.LEANBACK_LAUNCHER" <<<"$manifesto" || falha "sem LEANBACK_LAUNCHER"
# aapt2 imprime booleanos como "=true" (versões novas) ou "=(type 0x12)0xffffffff" (antigas).
verdadeiro='=(true|\(type 0x12\)0xffffffff)'
grep -Eq "usesCleartextTraffic(\(0x[0-9a-f]+\))?$verdadeiro" <<<"$manifesto" && falha "usesCleartextTraffic=true"
grep -q "networkSecurityConfig" <<<"$manifesto" || falha "sem networkSecurityConfig"
grep -q "dataExtractionRules" <<<"$manifesto" || falha "sem dataExtractionRules"

if [ "$modo" = "--release" ]; then
  grep -qx "application-debuggable" <<<"$badging" && falha "release debuggable"
  grep -Eq "debuggable(\(0x[0-9a-f]+\))?$verdadeiro" <<<"$manifesto" && falha "release debuggable"
fi

echo "pacote=$pacote versionCode=$vcode versionName=$vname minSdk=$minsdk targetSdk=$targetsdk compileSdk=$compilesdk"
echo "permissoes=$permissoes"
echo "OK"
