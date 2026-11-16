#!/usr/bin/env bash
# 本地 Anvil：部署 mock tUSD8 + InvoicePayment，开票，approve，pay，
# 后端索引到 Paid，并收到 HMAC webhook。
# 这里的私钥是 Anvil 公开的开发账户，不能拿到 Aevum 或任何真实网络上用。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="${HOME}/.foundry/bin:/usr/local/bin:${PATH}"
cd "$ROOT"
bash "$ROOT/scripts/bootstrap-contracts.sh"

# Anvil well-known keys. https://book.getfoundry.sh/anvil/
DEPLOYER_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
MERCHANT_KEY=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
PAYER_KEY=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
DEPLOYER=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
MERCHANT=0x70997970C51812dc3A010C7d01b50e0d17dc79C8

export RUN_DIR="${RUN_DIR:-/tmp/aevum-pay-e2e}"
APP_PORT="${APP_PORT:-18080}"
ANVIL_PORT="${ANVIL_PORT:-18545}"
HOOK_PORT="${HOOK_PORT:-18099}"
RPC="http://127.0.0.1:${ANVIL_PORT}"
mkdir -p "$RUN_DIR"
rm -f "$RUN_DIR/webhook.body" "$RUN_DIR/webhook.sig" "$RUN_DIR/app.log" "$RUN_DIR/anvil.log" "$RUN_DIR"/db*

port_busy() {
  python3 - "$1" << 'PY'
import socket, sys
sock = socket.socket()
sock.settimeout(0.2)
code = sock.connect_ex(("127.0.0.1", int(sys.argv[1])))
sys.exit(0 if code == 0 else 1)
PY
}

for port in "$APP_PORT" "$ANVIL_PORT" "$HOOK_PORT"; do
  if port_busy "$port"; then
    echo "port ${port} is already in use" >&2
    exit 1
  fi
done

cleanup() {
  if [[ -n "${APP_PID:-}" ]]; then kill "$APP_PID" >/dev/null 2>&1 || true; fi
  if [[ -n "${ANVIL_PID:-}" ]]; then kill "$ANVIL_PID" >/dev/null 2>&1 || true; fi
  if [[ -n "${HOOK_PID:-}" ]]; then kill "$HOOK_PID" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

echo "== anvil =="
anvil --host 127.0.0.1 --port "$ANVIL_PORT" --chain-id 31337 >"$RUN_DIR/anvil.log" 2>&1 &
ANVIL_PID=$!
for _ in $(seq 1 50); do
  if cast block-number --rpc-url "$RPC" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done
cast block-number --rpc-url "$RPC" >/dev/null

echo "== deploy mock tUSD8 + InvoicePayment =="
cd "$ROOT/contracts"
DEPLOYER_PRIVATE_KEY="$DEPLOYER_KEY" \
  forge script script/DeployLocal.s.sol --rpc-url "$RPC" --broadcast >"$RUN_DIR/deploy.log"
TOKEN="$(python3 - "$RUN_DIR/deploy.log" << 'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
found = re.findall(r"MOCK_TUSD8:?\s*(0x[0-9a-fA-F]{40})", text)
if not found:
    sys.exit("MOCK_TUSD8 not found in deploy log")
print(found[-1])
PY
)"
CONTRACT="$(python3 - "$RUN_DIR/deploy.log" << 'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
found = re.findall(r"INVOICE_PAYMENT:?\s*(0x[0-9a-fA-F]{40})", text)
if not found:
    sys.exit("INVOICE_PAYMENT not found in deploy log")
print(found[-1])
PY
)"
echo "token=${TOKEN}"
echo "contract=${CONTRACT}"

echo "== merchant authorizes operator =="
cast send "$CONTRACT" "setOperator(address,bool)" "$DEPLOYER" true \
  --private-key "$MERCHANT_KEY" --rpc-url "$RPC" >/dev/null

echo "== webhook receiver =="
python3 "$ROOT/scripts/webhook_receiver.py" "$RUN_DIR/webhook.body" "$RUN_DIR/webhook.sig" "$HOOK_PORT" \
  >"$RUN_DIR/webhook.log" 2>&1 &
HOOK_PID=$!

echo "== backend =="
cd "$ROOT"
mvn -q -DskipTests -f backend/pom.xml package
export SERVER_PORT="$APP_PORT"
export DATABASE_URL="jdbc:h2:file:${RUN_DIR}/db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
export DATABASE_USER=sa
export DATABASE_PASSWORD=sa
export AEVUM_RPC_URL="$RPC"
export AEVUM_PUBLIC_RPC_URL="$RPC"
export AEVUM_CHAIN_ID=31337
export CONTRACT_ADDRESS="$CONTRACT"
export TUSD8_ADDRESS="$TOKEN"
export START_BLOCK=0
export EXPLORER_URL=
export CHAIN_CONFIRMATIONS=0
export CHAIN_POLL_INTERVAL_MS=400
export WEBHOOK_DISPATCH_INTERVAL_MS=400
export RECONCILE_INTERVAL_MS=3600000
export PUBLIC_BASE_URL="http://127.0.0.1:${APP_PORT}"
export AEVUM_OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY"
java -jar backend/target/aevum-pay-backend-0.1.0.jar >"$RUN_DIR/app.log" 2>&1 &
APP_PID=$!

echo "== wait for health =="
ready=0
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo "backend exited" >&2
    tail -n 80 "$RUN_DIR/app.log" >&2 || true
    exit 1
  fi
  sleep 0.5
done
if [[ "$ready" != 1 ]]; then
  echo "backend did not become healthy" >&2
  tail -n 80 "$RUN_DIR/app.log" >&2 || true
  exit 1
fi

echo "== register merchant =="
python3 - "$APP_PORT" "$MERCHANT_KEY" "$HOOK_PORT" << 'PY'
import json, os, subprocess, sys, urllib.request
port, key, hook = sys.argv[1], sys.argv[2], sys.argv[3]
base = f"http://127.0.0.1:{port}"

def call(method, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)

merchant = subprocess.check_output(["cast", "wallet", "address", "--private-key", key], text=True).strip()
nonce = call("GET", "/api/v1/auth/nonce?address=" + merchant)
signature = subprocess.check_output(
    ["cast", "wallet", "sign", "--private-key", key, nonce["message"]], text=True
).strip()
registered = call("POST", "/api/v1/merchants", {
    "name": "E2E Shop",
    "walletAddress": merchant,
    "nonce": nonce["nonce"],
    "signature": signature,
    "webhookUrl": f"http://127.0.0.1:{hook}/hook",
})
open(os.environ["RUN_DIR"] + "/api.key", "w").write(registered["apiKey"])
open(os.environ["RUN_DIR"] + "/hook.secret", "w").write(registered["webhookSecret"])
print("registered", merchant)
PY

echo "== create invoice via operator =="
DUE="$(python3 - << 'PY'
from datetime import datetime, timedelta, timezone
print((datetime.now(timezone.utc) + timedelta(days=3)).strftime("%Y-%m-%dT%H:%M:%SZ"))
PY
)"
API_KEY="$(cat "$RUN_DIR/api.key")"
CREATE="$(curl -fsS -X POST "http://127.0.0.1:${APP_PORT}/api/v1/invoices" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "Idempotency-Key: e2e-1" \
  -d "{\"orderId\":\"ORD-E2E\",\"amount\":\"1000000\",\"dueAt\":\"${DUE}\",\"allowPartial\":false}")"
echo "$CREATE" | tee "$RUN_DIR/create.json"
INVOICE_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["chainInvoiceId"])' "$RUN_DIR/create.json")"
if [[ -z "$INVOICE_ID" || "$INVOICE_ID" == "None" ]]; then
  echo "create did not return a chain invoice id" >&2
  exit 1
fi

echo "== payer approves and pays invoice ${INVOICE_ID} =="
cast send "$TOKEN" "approve(address,uint256)" "$CONTRACT" 1000000 \
  --private-key "$PAYER_KEY" --rpc-url "$RPC" >/dev/null
cast send "$CONTRACT" "pay(uint256,uint256)" "$INVOICE_ID" 1000000 \
  --private-key "$PAYER_KEY" --rpc-url "$RPC" >/dev/null

echo "== wait until backend shows Paid and webhook arrives =="
python3 - "$APP_PORT" "$INVOICE_ID" "$RUN_DIR" "$CONTRACT" "$TOKEN" "$RPC" << 'PY'
import json, sys, time, urllib.request, subprocess
port, invoice_id, run_dir, contract, token, rpc = sys.argv[1:]
status = ""
for _ in range(40):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/public/invoices/{invoice_id}", timeout=5) as response:
        body = json.load(response)
    status = body.get("status")
    if status == "PAID":
        break
    time.sleep(0.5)
else:
    sys.exit(f"invoice status stayed {status}")

for _ in range(40):
    try:
        raw = open(run_dir + "/webhook.body", "rb").read()
    except FileNotFoundError:
        raw = b""
    if b"invoice.paid" in raw:
        break
    time.sleep(0.5)
else:
    sys.exit("webhook was not delivered")

import hashlib, hmac
secret = open(run_dir + "/hook.secret", encoding="utf-8").read().strip()
header = open(run_dir + "/webhook.sig", encoding="utf-8").read().strip()
parts = dict(piece.split("=", 1) for piece in header.split(","))
expected = hmac.new(secret.encode(), (parts["t"] + ".").encode() + raw, hashlib.sha256).hexdigest()
if not hmac.compare_digest(expected, parts["v1"]):
    sys.exit("webhook signature mismatch")
payload = json.loads(raw)
if str(payload.get("invoiceId")) != str(invoice_id):
    sys.exit(f"unexpected webhook payload {payload}")

balance = subprocess.check_output(
    ["cast", "call", token, "balanceOf(address)(uint256)", contract, "--rpc-url", rpc], text=True
).strip()
if balance not in {"0", "0x0"}:
    sys.exit(f"contract token balance is {balance}, expected 0")
print("e2e ok", payload.get("type"), "invoice", invoice_id, "contract balance", balance)
PY

echo "e2e passed"
