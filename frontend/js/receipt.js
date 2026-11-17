const $ = (id) => document.getElementById(id);

async function boot() {
  const match = location.pathname.match(/\/receipt\/(\d+)/);
  const id = match ? match[1] : "";
  const cfg = await Aevum.config();
  $("network").textContent = cfg.networkName;
  if (!id) {
    $("body").textContent = "缺少发票号";
    return;
  }
  const response = await fetch("/api/v1/public/invoices/" + id + "/receipt");
  const body = await response.json();
  if (!response.ok) {
    $("body").textContent = body.message || "找不到收据";
    return;
  }
  const invoice = body.invoice;
  const hash = invoice.lastTxHash || invoice.createTxHash || "";
  const explorer = Aevum.explorerTx(cfg, hash);
  const rows = [
    ["发票 / Invoice", invoice.chainInvoiceId],
    ["状态 / Status", invoice.status],
    ["商家 / Merchant", invoice.merchant],
    ["付款人 / Payer", invoice.payer || "—"],
    ["订单号 / Order", invoice.orderId || "—"],
    ["应付 / Amount", Aevum.formatAmount(invoice.amount, cfg.tokenDecimals) + " tUSD8"],
    ["已付 / Paid", Aevum.formatAmount(invoice.paidAmount, cfg.tokenDecimals) + " tUSD8"],
    ["已退 / Refunded", Aevum.formatAmount(invoice.refundedAmount, cfg.tokenDecimals) + " tUSD8"],
    ["截止 / Due", invoice.dueAt || "—"],
    ["交易 / Tx", hash || "—"]
  ];
  $("body").innerHTML = rows.map(([label, value]) =>
    `<div><span>${label}</span><strong class="mono">${escapeHtml(value)}</strong></div>`).join("");
  if (explorer) {
    $("explorer").href = explorer;
    $("explorer").hidden = false;
  }
  $("note").textContent = body.verification || "";
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[char]));
}

boot();
