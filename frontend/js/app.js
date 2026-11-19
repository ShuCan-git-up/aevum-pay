/* The browser only signs. Private keys stay in the wallet; the backend receives results, not keys. */
window.Aevum = (() => {
  const ERC20_ABI = [
    "function approve(address spender, uint256 amount) returns (bool)",
    "function allowance(address owner, address spender) view returns (uint256)",
    "function balanceOf(address) view returns (uint256)",
    "function decimals() view returns (uint8)",
    "function symbol() view returns (string)"
  ];

  const ERROR_TEXT = {
    InvoiceExpired: "This invoice has expired",
    InvoiceNotPayable: "This invoice can no longer be paid",
    NotPayer: "This invoice is locked to a different payer",
    Overpayment: "The amount is more than the remaining balance",
    PartialNotAllowed: "This invoice must be paid in full",
    InvoiceNotFound: "Invoice not found",
    NotMerchant: "Only the merchant can do this",
    NotMerchantOrOperator: "Only the merchant or operator can cancel",
    InvoiceNotCancellable: "This invoice cannot be cancelled after a payment",
    NotRefundable: "There is nothing to refund",
    OverRefund: "The refund is more than the amount paid",
    EnforcedPause: "Payments are paused",
    InvalidAmount: "Invalid amount"
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
        if (!response.ok) throw new Error("Network configuration is unavailable");
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
      throw new Error("No wallet found. Install OKX Wallet or any EIP-1193 wallet.");
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
    if (!cfg.contractAddress) throw new Error("CONTRACT_ADDRESS is not configured on the backend");
    return {
      cfg,
      payment: new ethers.Contract(cfg.contractAddress, paymentAbi, signerOrProvider),
      token: new ethers.Contract(cfg.tokenAddress, ERC20_ABI, signerOrProvider),
      iface: new ethers.Interface(paymentAbi)
    };
  }

  function parseAmount(human, decimals) {
    const text = String(human).trim();
    if (!/^\d+(\.\d+)?$/.test(text)) throw new Error("Enter a valid amount");
    const [whole, frac = ""] = text.split(".");
    if (frac.length > decimals) throw new Error("Too many decimal places (max " + decimals + ")");
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

  function midEllipsis(value, head = 10, tail = 8) {
    const text = String(value || "");
    if (text.length <= head + tail + 1) return text;
    return text.slice(0, head) + "…" + text.slice(-tail);
  }

  function formatTime(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleString();
  }

  function explain(error, iface) {
    const data = error?.data || error?.error?.data || error?.info?.error?.data;
    if (iface && typeof data === "string" && data.startsWith("0x")) {
      try {
        const parsed = iface.parseError(data);
        if (parsed && ERROR_TEXT[parsed.name]) return ERROR_TEXT[parsed.name];
        if (parsed) return parsed.name;
      } catch (_) { /* not one of our custom errors */ }
    }
    if (error?.code === 4001 || String(error?.message || "").toLowerCase().includes("user rejected")) {
      return "You rejected the signature";
    }
    return error?.shortMessage || error?.reason || error?.message || "The transaction failed";
  }

  function explorerTx(cfg, hash) {
    if (!cfg?.explorerUrl || !hash) return "";
    return cfg.explorerUrl.replace(/\/$/, "") + "/tx/" + hash;
  }

  function payPath(id) {
    return "/pay/" + id;
  }

  function receiptPath(id) {
    return "/receipt/" + id;
  }

  async function copyText(value, button) {
    try {
      await navigator.clipboard.writeText(value);
      if (button) {
        const previous = button.textContent;
        button.textContent = "Copied";
        setTimeout(() => { button.textContent = previous; }, 1200);
      }
    } catch (_) {
      if (button) button.textContent = "Copy failed";
    }
  }

  function renderTx(target, cfg, hash, label) {
    if (!target) return;
    target.replaceChildren();
    if (!hash) return;
    if (label) {
      const prefix = document.createElement("span");
      prefix.textContent = label + " ";
      target.append(prefix);
    }
    const wrap = document.createElement("span");
    wrap.className = "tx-hash";
    const href = explorerTx(cfg, hash);
    const display = midEllipsis(hash);
    if (href) {
      const link = document.createElement("a");
      link.href = href;
      link.target = "_blank";
      link.rel = "noreferrer";
      link.title = hash;
      link.textContent = display;
      wrap.append(link);
    } else {
      const text = document.createElement("span");
      text.title = hash;
      text.textContent = display;
      wrap.append(text);
    }
    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "copy";
    copy.textContent = "Copy";
    copy.addEventListener("click", () => copyText(hash, copy));
    wrap.append(copy);
    target.append(wrap);
  }

  return {
    provider, config, connect, ensureChain, contracts, parseAmount, formatAmount,
    short, midEllipsis, formatTime, explain, explorerTx, payPath, receiptPath,
    copyText, renderTx, ERROR_TEXT
  };
})();
