#!/usr/bin/env bash
set -euo pipefail

# Deployment script for Skylar Relay Forwarder and Issuer to OCI Always Free VM
# Usage: ./deploy-to-oci-vm.sh [inventory_file]

INVENTORY="${1:-$(dirname "$0")/inventory.yaml}"

echo "=== Deploying Skylar Infra to OCI VM ==="
echo "Using inventory: $INVENTORY"

# Read host and user from inventory
VM_HOST=$(grep -E '^\s*host:' "$INVENTORY" | awk -F '"' '{print $2}')
VM_USER=$(grep -E '^\s*user:' "$INVENTORY" | awk -F '"' '{print $2}')
TARGET_DIR=$(grep -E '^\s*target_dir:' "$INVENTORY" | awk -F '"' '{print $2}')

echo "Target: $VM_USER@$VM_HOST:$TARGET_DIR"

# Rsync configuration files excluding secrets
rsync -avz --delete \
  --exclude 'secrets.env' \
  --exclude 'tunnel-credentials.json' \
  --exclude '.git' \
  "$(dirname "$0")/../" "$VM_USER@$VM_HOST:$TARGET_DIR/"

# Trigger docker-compose up on the remote VM
ssh "$VM_USER@$VM_HOST" "cd $TARGET_DIR/compose && docker compose -f docker-compose.oci-vm.yml up -d --build"

echo "=== Deployment completed successfully ==="
