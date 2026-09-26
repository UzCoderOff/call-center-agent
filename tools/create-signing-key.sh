#!/usr/bin/env bash
# One-time: creates the app's release signing key.
#
# Every version of the app must be signed with the SAME key — otherwise
# Android refuses to install an update over the previous one. So:
#   1. Run this once:   bash tools/create-signing-key.sh
#   2. Put the four values it prints into GitHub:
#        repo -> Settings -> Secrets and variables -> Actions -> New secret
#   3. Keep ledger-release.p12 and its password somewhere safe (password
#      manager + a backup). Never commit it. If it is lost, the next app
#      version can only be installed after uninstalling the old one.
#
# Needs only openssl (included with Git for Windows / Git Bash, macOS, Linux).
set -euo pipefail

# Git Bash on Windows rewrites arguments that start with "/" into Windows
# paths, which breaks openssl's -subj "/CN=…". No effect elsewhere.
export MSYS_NO_PATHCONV=1

OUT="${1:-ledger-release.p12}"
ALIAS="ledger"

if [ -e "$OUT" ]; then
  echo "$OUT already exists — refusing to overwrite a signing key." >&2
  exit 1
fi

PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
# Relative on purpose: with path conversion off, a native Windows openssl
# can't open "/tmp/...", but relative paths work on every platform.
WORK="$(mktemp -d ./.ledger-key.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

openssl req -x509 -newkey rsa:4096 -sha256 -days 10950 -nodes \
  -keyout "$WORK/key.pem" -out "$WORK/cert.pem" \
  -subj "/CN=Ledger/O=Ledger" 2>/dev/null

openssl pkcs12 -export -name "$ALIAS" \
  -inkey "$WORK/key.pem" -in "$WORK/cert.pem" \
  -passout "pass:$PASSWORD" -out "$OUT"

echo
echo "Created $OUT. Add these as GitHub Actions secrets:"
echo
echo "  LEDGER_KEYSTORE_BASE64 = (the whole output of:  base64 -w0 $OUT )"
echo "  LEDGER_KEYSTORE_PASSWORD = $PASSWORD"
echo "  LEDGER_KEY_ALIAS = $ALIAS"
echo "  LEDGER_KEY_PASSWORD = $PASSWORD"
echo
echo "Store $OUT and the password safely. Do not commit them."
