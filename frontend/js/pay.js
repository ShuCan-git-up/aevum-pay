const $ = (id) => document.getElementById(id);
let cfg;
let iface;
let invoiceId;

function banner(text, kind) {
  const node = $("banner");
  node.className = "banner " + (kind || "");
  node.hidden = !text;
  node.textContent = text;
}

async function boot() {
  const match = location.pathname.match(/\/pay\/(\d+)/);
  invoiceId = match ? match[1] : "";
  cfg = await Aevum.config();
  $("network").textContent = cfg.networkName;
  if (!invoiceId) {
    banner("链接里没有发票号 / Missing invoice id", "error");
    return;
  }
  const abi = await fetch("/abi/InvoicePayment.json").then((response) => response.json());
  iface = new ethers.Interface(abi);
  $("connect").onclick = pay;
  await render();
  setInterval(render, 3000);
}

async function loadInvoice() {
  const response = await fetch("/api/v1/public/invoices/" + invoiceId);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || "发票不存在 / Invoice not found");
  }
  return response.json();
}

async function render() {
  try {
    const invoice = await loadInvoice();
    const decimals = cfg.tokenDecimals;
    const remaining = BigInt(invoice.remaining);
    $("amount").innerHTML = Aevum.formatAmount(invoice.amount, decimals) + " <small>tUSD8</small>";
    $("paid").textContent = Aevum.formatAmount(invoice.paidAmount, decimals) + " / " + Aevum.formatAmount(invoice.amount, decimals);
    $("merchant").textContent = invoice.merchant;
    $("payer").textContent = invoice.payer || "任何人，首付后锁定 / open, locked after first payment";
    $("status").className = "status " + invoice.status;
    $("status").textContent = invoice.status;
    $("due").textContent = invoice.dueAt ? new Date(invoice.dueAt).toLocaleString() : "—";
    $("order").textContent = invoice.orderId || "—";
    const partial = $("partial-wrap");
    partial.hidden = !invoice.allowPartial;
    if (!$("partial").value) $("partial").value = Aevum.formatAmount(remaining, decimals);
    const payable = invoice.status === "PENDING" || invoice.status === "PARTIALLY_PAID" || invoice.status === "CREATING";
    $("connect").disabled = !payable || remaining === 0n;
    $("connect").textContent = payable ? "连接钱包并支付 / Connect and pay" : invoice.status;
    if (invoice.lastTxHash || invoice.createTxHash) {
      const hash = invoice.lastTxHash || invoice.createTxHash;
      const href = Aevum.explorerTx(cfg, hash);
      $("tx").textContent = "";
      $("tx").insertAdjacentHTML("beforeend", "最近交易 / latest tx " + (href
        ? `<a href="${href}" target="_blank" rel="noreferrer">${hash}</a>`
        : hash));
    }
  } catch (error) {
    banner(error.message, "error");
  }
}

async function pay() {
  try {
    banner("");
    const session = await Aevum.connect();
    const invoice = await loadInvoice();
    const decimals = cfg.tokenDecimals;
    const remaining = BigInt(invoice.remaining);
    let amount = remaining;
    if (invoice.allowPartial) {
      amount = Aevum.parseAmount($("partial").value, decimals);
      if (amount <= 0n || amount > remaining) throw new Error("本次金额要大于 0 且不超过剩余应付");
    }
    const { payment, token } = await Aevum.contracts(session.signer);
    const balance = await token.balanceOf(session.address);
    if (balance < amount) {
      throw new Error("tUSD8 余额不足 / Insufficient tUSD8. 当前 " + Aevum.formatAmount(balance, decimals));
    }
    const allowance = await token.allowance(session.address, cfg.contractAddress);
    if (allowance < amount) {
      banner("请在钱包里授权本次 tUSD8 金额。默认只授权这一笔，不是无限额度。");
      const approveTx = await token.approve(cfg.contractAddress, amount);
      await approveTx.wait();
    }
    banner("请确认支付交易 / Confirm the payment");
    const tx = await payment.pay(invoiceId, amount);
    banner("交易已提交，等待确认 " + tx.hash);
    const receipt = await tx.wait();
    const href = Aevum.explorerTx(cfg, tx.hash);
    $("tx").innerHTML = "最近交易 / latest tx " + (href
      ? `<a href="${href}" target="_blank" rel="noreferrer">${tx.hash}</a>`
      : tx.hash);
    banner("付款交易已确认。收据页：/receipt/" + invoiceId, "ok");
    if (receipt) await render();
    location.href = "/receipt/" + invoiceId;
  } catch (error) {
    banner(Aevum.explain(error, iface), "error");
  }
}

boot().catch((error) => banner(error.message, "error"));
