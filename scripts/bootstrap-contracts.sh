#!/usr/bin/env bash
# 安装 Foundry 依赖。lib/ 不进 git，体积大，而且 OpenZeppelin 自己还带了子模块。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/contracts"
if [[ ! -f lib/forge-std/src/Script.sol ]]; then
  forge install foundry-rs/forge-std --no-git
fi
if [[ ! -f lib/openzeppelin-contracts/contracts/token/ERC20/IERC20.sol ]]; then
  forge install OpenZeppelin/openzeppelin-contracts@v5.2.0 --no-git
fi
