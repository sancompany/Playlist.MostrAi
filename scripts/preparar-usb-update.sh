#!/usr/bin/env bash
# Monta o pacote OFICIAL de atualização por pendrive a partir do APK de
# release assinado com a chave definitiva:
#
#   <destino>/Mostrai-USB-<versão>/
#   └── MOSTRAI/
#       └── update/
#           ├── Mostrai-Player.apk
#           └── update.json
#   <destino>/Mostrai-USB-<versão>.zip   (a mesma árvore + LEIA-ME.txt)
#
# Confere antes (e recusa se falhar): scripts/verificar-apk.sh --release
# (pacote, SDK, permissões, não-debuggable), assinatura válida e impressão
# digital igual à da chave definitiva (scripts/certificado-producao.sha256).
# O update.json só ajuda a pegar erro de empacotamento — o Player confere o
# APK de verdade.
#
# Uso: scripts/preparar-usb-update.sh <apk-release-assinado> [destino] [--teste]
#   --teste  aceita um APK de teste N → N+1 (versionName "…-teste-…"); o
#            pacote sai marcado NAO-DISTRIBUIR.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

apk="${1:?uso: $0 <apk-release-assinado> [destino] [--teste]}"
destino="${2:-app/build/usb}"
modo="${3:-}"
[ "$destino" = "--teste" ] && { modo="--teste"; destino="app/build/usb"; }
[ -f "$apk" ] || { echo "APK não encontrado: $apk" >&2; exit 2; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "ANDROID_HOME não definido" >&2; exit 2; }
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"

falha() { echo "FALHOU: $*" >&2; exit 1; }

verificacao="$(scripts/verificar-apk.sh "$apk" --release)"
versao="$(sed -n 's/.*versionName=\([^ ]*\).*/\1/p' <<<"$verificacao" | head -1)"
vcode="$(sed -n 's/.*versionCode=\([^ ]*\).*/\1/p' <<<"$verificacao" | head -1)"
case "$versao" in
  *teste*) [ "$modo" = "--teste" ] || falha "APK de teste ($versao); para o teste N → N+1 use --teste" ;;
  *) [ "$modo" != "--teste" ] || falha "--teste só para APK de teste" ;;
esac

"$bt/apksigner" verify --min-sdk-version 26 "$apk" >/dev/null || falha "assinatura inválida"
impressao="$("$bt/apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | tr 'A-F' 'a-f')"
oficial="$(tr -d ' \n' < scripts/certificado-producao.sha256 | tr 'A-F' 'a-f')"
[ "$impressao" = "$oficial" ] || falha "APK não assinado com a chave definitiva ($impressao)"

sha="$(sha256sum "$apk" | cut -d' ' -f1)"
nome="Mostrai-USB-$versao"
[ "$modo" = "--teste" ] && nome="$nome-NAO-DISTRIBUIR"
pasta="$destino/$nome"
rm -rf "$pasta" "$destino/$nome.zip"
mkdir -p "$pasta/MOSTRAI/update"
cp "$apk" "$pasta/MOSTRAI/update/Mostrai-Player.apk"
printf '{\n  "versionName": "%s",\n  "versionCode": %s,\n  "sha256": "%s",\n  "certificateSha256": "%s"\n}\n' \
  "$versao" "$vcode" "$sha" "$oficial" > "$pasta/MOSTRAI/update/update.json"
cat > "$pasta/LEIA-ME.txt" <<TXT
Atualização Mostraí Player $versao (versionCode $vcode)$( [ "$modo" = "--teste" ] && echo " — TESTE, NÃO DISTRIBUIR")

1. Copie a pasta MOSTRAI para a RAIZ de um pendrive (FAT32 ou exFAT).
2. Com o Mostraí Player 3.0.1 ou mais novo rodando na TV, conecte o pendrive.
3. Aparece "ATUALIZAÇÃO MOSTRAÍ" → Atualizar agora → confirme no Android.
   (Na primeira vez o Android pede para permitir o acesso ao pendrive e/ou
   liberar a instalação pelo Mostraí Player.)
4. O Player volta sozinho na versão nova, com a mesma tela e o mesmo cadastro.

TV com Mostraí 3.0.0 (sem atualizador): abra MOSTRAI/update/Mostrai-Player.apk
pelo gerenciador de arquivos da TV — mesma chave, os dados ficam.

SHA-256 do APK: $sha
Certificado (SHA-256): $oficial
TXT
python3 - "$destino" "$nome" <<'PY'
import os, sys, zipfile
destino, nome = sys.argv[1], sys.argv[2]
raiz = os.path.join(destino, nome)
with zipfile.ZipFile(os.path.join(destino, nome + ".zip"), "w", zipfile.ZIP_DEFLATED) as z:
    for pasta, _, arquivos in os.walk(raiz):
        for a in sorted(arquivos):
            caminho = os.path.join(pasta, a)
            z.write(caminho, os.path.relpath(caminho, raiz))
PY

echo "pacote: $pasta"
echo "zip: $destino/$nome.zip"
echo "zip_sha256: $(sha256sum "$destino/$nome.zip" | cut -d' ' -f1)"
echo "apk_sha256: $sha"
echo "versao: $versao ($vcode)"
echo "certificado: $oficial"
