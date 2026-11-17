#!/usr/bin/env bash
# 一条命令在本机跑起 Anvil、mock tUSD8、合约和后端（后端同时提供页面）。
# 钱包导入脚本打印的 Anvil 开发私钥即可在浏览器里走完整流程。
# 这些私钥是公开测试钥，不要用于 Aevum。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="${HOME}/.foundry/bin:/usr/local/bin:${PATH}"
bash "$ROOT/scripts/bootstrap-contracts.sh"

DEPLOYER_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
MERCHANT_KEY=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
PAYER_KEY=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
DEPLOYER=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
MERCHANT=0x70997970C51812dc3A010C7d01b50e0d17dc79C8
PAYER=0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC

RUN_DIR="${RUN_DIR:-/tmp/aevum-pay-dev}"
APP_PORT="${APP_PORT:-8080}"
ANVIL_PORT="${ANVIL_PORT:-8545}"
RPC="http://127.0.0.1:${ANVIL_PORT}"
mkdir -p "$RUN_DIR"

cleanup() {
  if [[ -n "${APP_PID:-}" ]]; then kill "$APP_PID" >/dev/null 2>&1 || true; fi
  if [[ -n "${ANVIL_PID:-}" ]]; then kill "$ANVIL_PID" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT INT TERM

anvil --host 127.0.0.1 --port "$ANVIL_PORT" --chain-id 31337 >"$RUN_DIR/anvil.log" 2>&1 &
ANVIL_PID=$!
for _ in $(seq 1 50); do
  if cast block-number --rpc-url "$RPC" >/dev/null 2>&1; then break; fi
  sleep 0.2
done

cd "$ROOT/contracts"
DEPLOYER_PRIVATE_KEY="$DEPLOYER_KEY" \
  forge script script/DeployLocal.s.sol --rpc-url "$RPC" --broadcast >"$RUN_DIR/deploy.log"
TOKEN="$(python3 - "$RUN_DIR/deploy.log" << 'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
print(re.findall(r"MOCK_TUSD8:?\s*(0x[0-9a-fA-F]{40})", text)[-1])
PY
)"
CONTRACT="$(python3 - "$RUN_DIR/deploy.log" << 'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
print(re.findall(r"INVOICE_PAYMENT:?\s*(0x[0-9a-fA-F]{40})", text)[-1])
PY
)"
cast send "$CONTRACT" "setOperator(address,bool)" "$DEPLOYER" true \
  --private-key "$MERCHANT_KEY" --rpc-url "$RPC" >/dev/null

cd "$ROOT"
if [[ ! -f backend/target/aevum-pay-backend-0.1.0.jar ]]; then
  mvn -q -DskipTests -f backend/pom.xml package
fi
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
export CHAIN_POLL_INTERVAL_MS=1000
export WEBHOOK_DISPATCH_INTERVAL_MS=1000
export PUBLIC_BASE_URL="http://127.0.0.1:${APP_PORT}"
export AEVUM_OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY"
java -jar backend/target/aevum-pay-backend-0.1.0.jar >"$RUN_DIR/app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done

cat << EOF

Aevum Pay 已在本机启动。Anvil 开发私钥只属于这条本地链。

  商家后台   http://127.0.0.1:${APP_PORT}/
  RPC        ${RPC}   chainId 31337 (0x7a69)
  合约       ${CONTRACT}
  mock tUSD8 ${TOKEN}

  导入钱包（Anvil 公开账户，不要用于 Aevum）：
    商家 / merchant  ${MERCHANT}
      ${MERCHANT_KEY}
    付款人 / payer   ${PAYER}
      ${PAYER_KEY}
    操作员已由部署账户授权，商家账户已 mint 了 tUSD8，可用于退款。

  Ctrl-C 停止。日志：${RUN_DIR}/app.log

EOF

wait "$APP_PID"
