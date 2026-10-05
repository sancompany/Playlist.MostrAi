#!/usr/bin/env bash
# Varredura de segredos no que está versionado (CI e antes de cada release).
# Falha se aparecer chave de assinatura, arquivo de senha da keystore,
# chave privada, token conhecido ou credencial de aparelho no Git — no
# conteúdo atual e no histórico. Não imprime o valor achado, só onde.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

erro=0
avisar() { echo "SEGREDO? $*" >&2; erro=1; }

# 1. Arquivos que nunca podem estar no Git.
while IFS= read -r f; do
  case "$f" in
    *.jks|*.keystore|*.p12|*.pk8|keystore.properties|signing.properties|*/keystore.properties|mostrai-config.json|*/mostrai-config.json|local.properties)
      avisar "arquivo versionado: $f" ;;
  esac
done < <(git ls-files)

# 2. Padrões no conteúdo versionado (exceto este script). Senha só conta
#    com valor literal até o fim da linha — `storePassword=...` (exemplo do
#    RUNBOOK) e `storePassword = propriedades.getProperty(...)` não contam.
padroes='-----BEGIN ([A-Z]+ )?PRIVATE KEY-----|(store|key)Password *= *["'"'"']?[A-Za-z0-9!@#$%^&*_+-]{4,}["'"'"']? *$|sk_live_[0-9A-Za-z]{10,}|ghp_[0-9A-Za-z]{30,}|github_pat_[0-9A-Za-z_]{30,}|AKIA[0-9A-Z]{16}|eyJhbGciOi[0-9A-Za-z_-]{20,}[.]|chaveAparelho"? *[:=] *"[A-Za-z0-9_-]{20,}"'
achados="$(mktemp)"
set +e
git grep -nIE -e "$padroes" -- . ':!scripts/varrer-segredos.sh' >"$achados"
rc=$?
set -e
# git grep: 0 = achou, 1 = nada, outro = a varredura nem rodou (nunca "OK").
if [ "$rc" -gt 1 ]; then echo "varredura de segredos falhou (git grep $rc)" >&2; rm -f "$achados"; exit 2; fi
if [ "$rc" = 0 ]; then
  while IFS= read -r onde; do avisar "padrão de segredo em $onde"; done < <(cut -d: -f1,2 "$achados")
fi
rm -f "$achados"

# 3. Histórico: arquivo de chave que já tenha entrado alguma vez.
# Capturado antes: grep -q num pipe com pipefail perderia o achado por SIGPIPE.
historico="$(git log --all --name-only --format=)"
if grep -E '\.(jks|keystore|p12|pk8)$|(^|/)keystore\.properties$' <<<"$historico" >/dev/null; then
  avisar "arquivo de chave no histórico do Git"
fi

[ "$erro" = 0 ] && echo "varredura de segredos: OK"
exit "$erro"
