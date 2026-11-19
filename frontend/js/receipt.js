const $ = (id) => document.getElementById(id);

async function boot() {
  const match = location.pathname.match(/\/receipt\/(\d+)/);
  const id = match ? match[1] : "";
  const cfg = await Aevum.config();
  $("network").textContent = cfg.networkName;
  if (!id) {
    $("body").textContent = "This link is missing an invoice ID";
    return;
  }
  const response = await fetch("/api/v1/public/invoices/" + id + "/receipt");
  const body = await response.json();
  if (!response.ok) {
    $("body").textContent = body.message || "Receipt not found";
    return;
  }
  const invoice = body.invoice;
  const hash = invoice.lastTxHash || invoice.createTxHash || "";
  const explorer = Aevum.explorerTx(cfg, hash);
  const rows = [
    ["Invoice", invoice.chainInvoiceId, "plain"],
    ["Status", invoice.status, "plain"],
    ["Merchant", invoice.merchant, "mono"],
    ["Payer", invoice.payer || "—", "mono"],
    ["Order", invoice.orderId || "—", "plain"],
    ["Amount", Aevum.formatAmount(invoice.amount, cfg.tokenDecimals) + " tUSD8", "plain"],
    ["Paid", Aevum.formatAmount(invoice.paidAmount, cfg.tokenDecimals) + " tUSD8", "plain"],
    ["Refunded", Aevum.formatAmount(invoice.refundedAmount, cfg.tokenDecimals) + " tUSD8", "plain"],
    ["Due", Aevum.formatTime(invoice.dueAt), "plain"],
    ["Transaction", hash, "tx"]
  ];
  const root = $("body");
  root.replaceChildren();
  for (const [label, value, kind] of rows) {
    const item = document.createElement("div");
    const caption = document.createElement("span");
    caption.textContent = label;
    item.append(caption);
    if (kind === "tx") {
      const strong = document.createElement("strong");
      strong.className = "tx-value";
      if (value) {
        Aevum.renderTx(strong, cfg, value);
      } else {
        strong.textContent = "—";
      }
      item.append(strong);
    } else {
      const strong = document.createElement("strong");
      if (kind === "mono") strong.classList.add("mono");
      strong.textContent = value ?? "—";
      item.append(strong);
    }
    root.append(item);
  }
  if (explorer) {
    $("explorer").href = explorer;
    $("explorer").hidden = false;
  }
  $("note").textContent = body.verification || "";
}

boot();
