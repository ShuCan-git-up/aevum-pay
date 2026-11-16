# 部署到 Aevum 测试网，再在腾讯云上把服务拉起来

这个文件只描述你在自己的机器和服务器上要做的事。仓库里没有私钥，本流程也不会替你发交易。

## 0. 服务器上已经有的东西

- Ubuntu，Docker 与 Compose
- 现有 Caddy 容器在 `80/443`，并接入 Docker 网络 `private-room-net`
- 本项目**不**占用 80/443

起来之后：

| 容器名 | 端口 | 谁访问 |
| --- | --- | --- |
| `aevum-pay-app` | `8080`（只在 Docker 网络里） | Caddy 反代；页面和 `/api` 都在这一个端口 |
| `aevum-pay-db` | 无宿主机端口 | 只和 `aevum-pay-app` 在内部网络通信 |

Caddy 站点示例：

```caddy
pay.example.com {
    reverse_proxy aevum-pay-app:8080
}
```

Caddy 容器必须和 `aevum-pay-app` 同在 `private-room-net`。

## 1. 部署合约

在一台装了 Foundry 的机器上（不必是云服务器）：

```bash
git clone <this-repo>
cd aevum-pay
bash scripts/bootstrap-contracts.sh
cd contracts

# 私钥只放在当前 shell。不要写进文件再提交。
export DEPLOYER_PRIVATE_KEY=0x你的测试私钥
export TUSD8_ADDRESS=0x456569838f1d43009A91ebA4263147Afd7233483

forge script script/Deploy.s.sol \
  --rpc-url aevum_testnet \
  --broadcast
```

`foundry.toml` 里的 `aevum_testnet` 就是 `https://rpc-test.qday.info`。编译目标是 Solidity 0.8.24、`evm_version = shanghai`。

脚本会打印：

```text
INVOICE_PAYMENT 0x...
TOKEN 0x456569838f1d43009A91ebA4263147Afd7233483
OWNER 0x...
CHAIN_ID 44005
```

记下合约地址。部署区块号：

```bash
# 把下面的交易哈希换成 broadcast 日志里的那笔
cast receipt <DEPLOY_TX_HASH> --rpc-url https://rpc-test.qday.info
```

`blockNumber` 就是 `START_BLOCK`。设得比部署区块更早只会多扫几块；设得更晚会漏掉部署之后、启动之前的发票。

部署账户需要 tQDAY 付 gas。可以从 https://faucet.qday.info/en 领取。

`script/DeployLocal.s.sol` 会拒绝 chainId 44005。那份脚本只给本地 Anvil 部署可增发的 mock tUSD8，不能拿到测试网上用。

## 2. 准备服务器上的 .env

```bash
cd /opt/aevum-pay   # 你的检出目录
cp .env.example .env
chmod 600 .env
```

至少填这些：

| 变量 | 值 |
| --- | --- |
| `CONTRACT_ADDRESS` | 上一步的 InvoicePayment 地址 |
| `START_BLOCK` | 部署交易的区块号 |
| `TUSD8_ADDRESS` | `0x456569838f1d43009A91ebA4263147Afd7233483` |
| `AEVUM_RPC_URL` | `https://rpc-test.qday.info` |
| `AEVUM_CHAIN_ID` | `44005` |
| `EXPLORER_URL` | `https://explorer-test.qday.info` |
| `CHAIN_CONFIRMATIONS` | `2` |
| `PUBLIC_BASE_URL` | `https://pay.example.com`（不要末尾斜杠也行，代码会去掉） |
| `POSTGRES_PASSWORD` | 自己设一个，不要用示例里的空值 |
| `AEVUM_OPERATOR_PRIVATE_KEY` | 操作员热钱包私钥，**或**改用下面的 keystore |

操作员热钱包只用来代开票和取消未付款发票，需要少量 tQDAY。它动不了用户的 tUSD8。可以和部署账户是同一个，也可以分开。

不想把私钥写成环境变量时，把 keystore 放到宿主机 `/opt/aevum-pay/secrets/`（权限 600），在 `docker-compose.yml` 的 `app` 服务下加：

```yaml
volumes:
  - /opt/aevum-pay/secrets:/secrets:ro
```

然后：

```bash
AEVUM_OPERATOR_PRIVATE_KEY=
AEVUM_OPERATOR_KEYSTORE=/secrets/operator.json
AEVUM_OPERATOR_KEYSTORE_PASSWORD=keystore口令
```

两个都空着时，后端只做索引和页面，不能代发交易。商家仍然可以用钱包自己开票。

## 3. 启动

确认外部网络在：

```bash
docker network inspect private-room-net >/dev/null
```

没有的话不要新建一个同名网络盖住现有 Caddy。先让 Caddy 所在的网络保持原样。

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f app
```

健康检查：在 `private-room-net` 里访问 `http://aevum-pay-app:8080/actuator/health`。日志里应有 `chainId=44005`、合约地址，以及操作员地址（不是私钥）。

JVM 堆上限约 320 MB（`-Xmx320m`），容器内存上限 480 MB。Postgres 容器上限 192 MB，`shared_buffers=32MB`。

## 4. 商家第一次使用

1. 打开 `https://pay.example.com/`
2. 连接 OKX Wallet（或任何注入的 EIP-1193 钱包）。页面会 `wallet_switchEthereumChain`，没有这条链时再 `wallet_addEthereumChain`（chainId `0xabe5`）
3. 如果要用 REST 代开票：点「授权操作员」。这只给操作员 `createInvoiceFor` / `cancelInvoice` 的权限
4. 钱包里直接开票，或用 API Key 调 `POST /api/v1/invoices`
5. 把 `/pay/{chainInvoiceId}` 发给付款人。付款人授权本次金额的 tUSD8，再 `pay`
6. 后台列表几秒内变成 Paid。如果配置了 Webhook，会收到 `invoice.paid`

付款人需要自己的 tUSD8 和 tQDAY。水龙头：https://faucet.qday.info/en

## 5. 更新

```bash
git pull
docker compose up -d --build
```

数据库卷 `pgdata` 会保留游标和发票读模型。链上状态仍然是真相来源，库可以删掉重建，索引器会从 `START_BLOCK` 再扫一遍。
