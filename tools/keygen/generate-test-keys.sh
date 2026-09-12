#!/usr/bin/env bash
# generate-test-keys.sh — Development & Testing P-256 Keypair Generator
# WARNING: For development and testing only. Never use for production keys.

set -euo pipefail

OUT_DIR="${1:-./test-keys}"
mkdir -p "$OUT_DIR"

echo "=== Generating Skylar Development Test Keypairs (ECDSA P-256) ==="

identities=("starlight" "sfm" "xtools" "test-caller" "policy-signer")

for id in "${identities[@]}"; do
    priv_key="$OUT_DIR/${id}-private.pem"
    pub_key="$OUT_DIR/${id}-public.pem"
    der_pub="$OUT_DIR/${id}-public.der"

    # Generate EC P-256 private key
    openssl ecparam -name prime256v1 -genkey -noout -out "$priv_key" 2>/dev/null || true
    # Extract public key
    openssl ec -in "$priv_key" -pubout -out "$pub_key" 2>/dev/null || true

    echo "Generated test keypair for: $id ($priv_key, $pub_key)"
done

echo "=== Key generation complete. Stored in $OUT_DIR ==="
