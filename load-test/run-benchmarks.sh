#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_URL="${1:-http://localhost:8080}"
SCENARIO="${2:-all}"

echo "=================================================="
echo " Mini Wallet Service - k6 High Load Testing Suite"
echo " Target: ${TARGET_URL}"
echo "=================================================="

run_k6() {
  local script_name="$1"
  local label="$2"
  echo ""
  echo ">>> Running Benchmark: ${label} (${script_name})"
  echo "--------------------------------------------------"
  docker run --rm --net=host -v "${SCRIPT_DIR}:/scripts" -w /scripts grafana/k6 run "${script_name}"
}

case "${SCENARIO}" in
  "read")
    run_k6 "balance-read.js" "Balance Inquiries (Read TPS up to 100 VUs)"
    ;;
  "heavy-read")
    run_k6 "heavy-read-stress.js" "Extreme Read Stress (Up to 500 VUs)"
    ;;
  "create")
    run_k6 "wallet-create.js" "Wallet Creation (Write TPS up to 100 VUs)"
    ;;
  "add-money")
    run_k6 "add-money-ingest.js" "Add-Money Ingestion TPS"
    ;;
  "transfer")
    run_k6 "transfers.js" "P2P Transfers TPS (Normal Concurrency)"
    ;;
  "transfer-stress")
    run_k6 "transfer-stress-test.js" "Heavy Transfer Stress (Up to 120 VUs)"
    ;;
  "mixed")
    run_k6 "mixed-workload.js" "Heavy Mixed Workload (Up to 300 VUs)"
    ;;
  "arrival-rate")
    run_k6 "max-throughput-arrival-rate.js" "Target Arrival Rate (Up to 6000 req/s)"
    ;;
  "all")
    run_k6 "balance-read.js" "1. Balance Inquiries (Read TPS)"
    run_k6 "wallet-create.js" "2. Wallet Creation (Write TPS)"
    run_k6 "add-money-ingest.js" "3. Add-Money Ingestion TPS"
    run_k6 "transfers.js" "4. P2P Transfers TPS"
    run_k6 "mixed-workload.js" "5. Heavy Mixed Workload (Up to 300 VUs)"
    run_k6 "transfer-stress-test.js" "6. Heavy Transfer Stress (Up to 120 VUs)"
    run_k6 "heavy-read-stress.js" "7. Extreme Read Stress (Up to 500 VUs)"
    ;;
  *)
    echo "Usage: $0 [TARGET_URL] [all|read|heavy-read|create|add-money|transfer|transfer-stress|mixed|arrival-rate]"
    exit 1
    ;;
esac

echo ""
echo "=================================================="
echo " Load Testing Run Complete!"
echo "=================================================="
