/* 浏览器侧只签名，不把私钥交给后端。类似前端自己拿用户的 HttpSession，服务器只收结果。 */
window.Aevum = (() => {
  const ERC20_ABI = [
    "function approve(address spender, uint256 amount) returns (bool)",
    "function allowance(address owner, address spender) view returns (uint256)",
    "function balanceOf(address) view returns (uint256)",
    "function decimals() view returns (uint8)",
    "function symbol() view returns (string)"
  ];

  const ERROR_TEXT = {
    InvoiceExpired: "发票已过期 / Invoice expired",
    InvoiceNotPayable: "这张发票不能再付 / Invoice is not payable",
    NotPayer: "这张发票指定了别的付款人 / You are not the payer",
    Overpayment: "金额超过剩余应付 / Amount exceeds the remaining balance",
    PartialNotAllowed: "这张发票必须一次付清 / Partial payment is not allowed",
    InvoiceNotFound: "找不到这张发票 / Invoice not found",
    NotMerchant: "只有商家本人可以操作 / Merchant only",
    NotMerchantOrOperator: "只有商家或操作员可以取消 / Merchant or operator only",
    InvoiceNotCancellable: "已经收过款，不能取消 / Cannot cancel after a payment",
    NotRefundable: "没有可退的金额 / Nothing to refund",
    OverRefund: "退款超过已付金额 / Refund exceeds the amount paid",
    EnforcedPause: "合约已暂停付款 / Payments are paused",
    InvalidAmount: "金额不合法 / Invalid amount"
  };

  let configPromise;
  let abiPromise;

  function provider() {
    const okx = window.okxwallet;
    if (okx && typeof okx.request === "function") return okx;
    if (okx && okx.ethereum && typeof okx.ethereum.request === "function") return okx.ethereum;
    if (window.ethereum && typeof window.ethereum.request === "function") return window.ethereum;
    return null;
  }

  async function config() {
    if (!configPromise) {
      configPromise = fetch("/api/v1/public/config").then((response) => {
        if (!response.ok) throw new Error("无法读取网络配置 / config unavailable");
        return response.json();
      });
    }
    return configPromise;
  }

  async function abi() {
    if (!abiPromise) {
      abiPromise = fetch("/abi/InvoicePayment.json").then((response) => response.json());
    }
    return abiPromise;
  }

  async function connect() {
    const injected = provider();
    if (!injected) {
      throw new Error("没有检测到钱包。请安装 OKX Wallet 或任何 EIP-1193 钱包 / No injected wallet");
    }
    const accounts = await injected.request({ method: "eth_requestAccounts" });
    await ensureChain(injected);
    const browser = new ethers.BrowserProvider(injected);
    const signer = await browser.getSigner();
    return { provider: injected, browser, signer, address: accounts[0] };
  }

  async function ensureChain(injected) {
    const cfg = await config();
    const current = await injected.request({ method: "eth_chainId" });
    if (current.toLowerCase() === cfg.chainIdHex.toLowerCase()) return;
    try {
      await injected.request({ method: "wallet_switchEthereumChain", params: [{ chainId: cfg.chainIdHex }] });
    } catch (error) {
      const missing = error && (error.code === 4902 || error.code === -32603);
      if (!missing) throw error;
      const params = {
        chainId: cfg.chainIdHex,
        chainName: cfg.networkName,
        nativeCurrency: { name: cfg.nativeSymbol, symbol: cfg.nativeSymbol, decimals: 18 },
        rpcUrls: [cfg.rpcUrl]
      };
      if (cfg.explorerUrl) params.blockExplorerUrls = [cfg.explorerUrl];
      await injected.request({ method: "wallet_addEthereumChain", params: [params] });
    }
  }

  async function contracts(signerOrProvider) {
    const cfg = await config();
    const paymentAbi = await abi();
    if (!cfg.contractAddress) throw new Error("后端还没配置 CONTRACT_ADDRESS");
    return {
      cfg,
      payment: new ethers.Contract(cfg.contractAddress, paymentAbi, signerOrProvider),
      token: new ethers.Contract(cfg.tokenAddress, ERC20_ABI, signerOrProvider),
      iface: new ethers.Interface(paymentAbi)
    };
  }

  function parseAmount(human, decimals) {
    const text = String(human).trim();
    if (!/^\d+(\.\d+)?$/.test(text)) throw new Error("金额格式不对 / Bad amount");
    const [whole, frac = ""] = text.split(".");
    if (frac.length > decimals) throw new Error("小数位超过 " + decimals);
    const padded = (frac + "0".repeat(decimals)).slice(0, decimals);
    return BigInt(whole) * 10n ** BigInt(decimals) + BigInt(padded || "0");
  }

  function formatAmount(base, decimals) {
    const value = BigInt(base);
    const negative = value < 0n;
    const abs = (negative ? -value : value).toString().padStart(decimals + 1, "0");
    const whole = abs.slice(0, -decimals);
    const frac = abs.slice(-decimals).replace(/0+$/, "");
    return (negative ? "-" : "") + whole + (frac ? "." + frac : "");
  }

  function short(address) {
    if (!address) return "—";
    return address.slice(0, 6) + "…" + address.slice(-4);
  }

  function explain(error, iface) {
    const data = error?.data || error?.error?.data || error?.info?.error?.data;
    if (iface && typeof data === "string" && data.startsWith("0x")) {
      try {
        const parsed = iface.parseError(data);
        if (parsed && ERROR_TEXT[parsed.name]) return ERROR_TEXT[parsed.name];
        if (parsed) return parsed.name;
      } catch (_) { /* 不是我们的 custom error */ }
    }
    if (error?.code === 4001 || String(error?.message || "").toLowerCase().includes("user rejected")) {
      return "你取消了签名 / Signature rejected";
    }
    return error?.shortMessage || error?.reason || error?.message || "交易失败 / Transaction failed";
  }

  function explorerTx(cfg, hash) {
    if (!cfg.explorerUrl || !hash) return "";
    return cfg.explorerUrl.replace(/\/$/, "") + "/tx/" + hash;
  }

  function payPath(id) {
    return "/pay/" + id;
  }

  return {
    provider, config, connect, ensureChain, contracts, parseAmount, formatAmount,
    short, explain, explorerTx, payPath, ERROR_TEXT
  };
})();
