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
    banner("This link is missing an invoice ID", "error");
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
    throw new Error(body.message || "Invoice not found");
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
    $("payer").textContent = invoice.payer || "Anyone can pay; the first payment locks the payer";
    $("status").className = "status " + invoice.status;
    $("status").textContent = invoice.status;
    $("due").textContent = Aevum.formatTime(invoice.dueAt);
    $("order").textContent = invoice.orderId || "—";
    const partial = $("partial-wrap");
    partial.hidden = !invoice.allowPartial;
    if (!$("partial").value) $("partial").value = Aevum.formatAmount(remaining, decimals);
    const payable = invoice.status === "PENDING" || invoice.status === "PARTIALLY_PAID" || invoice.status === "CREATING";
    $("connect").hidden = !payable;
    $("connect").disabled = !payable || remaining === 0n;
    $("connect").textContent = "Connect wallet and pay";
    const receipt = $("receipt-link");
    const showReceipt = invoice.status === "PAID" || invoice.status === "PARTIALLY_PAID"
      || invoice.status === "REFUNDED" || BigInt(invoice.paidAmount) > 0n;
    receipt.hidden = !showReceipt;
    receipt.href = Aevum.receiptPath(invoice.chainInvoiceId || invoiceId);
    const hash = invoice.lastTxHash || invoice.createTxHash;
    if (hash) {
      Aevum.renderTx($("tx"), cfg, hash, "Latest transaction");
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
      if (amount <= 0n || amount > remaining) {
        throw new Error("This payment must be greater than 0 and no more than the remaining balance");
      }
    }
    const { payment, token } = await Aevum.contracts(session.signer);
    const balance = await token.balanceOf(session.address);
    if (balance < amount) {
      throw new Error("Insufficient tUSD8. Balance: " + Aevum.formatAmount(balance, decimals));
    }
    const allowance = await token.allowance(session.address, cfg.contractAddress);
    if (allowance < amount) {
      banner("Approve this tUSD8 amount in your wallet. Only this payment is approved, not an unlimited allowance.");
      const approveTx = await token.approve(cfg.contractAddress, amount);
      await approveTx.wait();
    }
    banner("Confirm the payment in your wallet");
    const tx = await payment.pay(invoiceId, amount);
    banner("Transaction submitted, waiting for confirmation " + Aevum.midEllipsis(tx.hash));
    const receipt = await tx.wait();
    Aevum.renderTx($("tx"), cfg, tx.hash, "Latest transaction");
    banner("Payment confirmed. Opening the receipt…", "ok");
    if (receipt) await render();
    location.href = Aevum.receiptPath(invoiceId);
  } catch (error) {
    banner(Aevum.explain(error, iface), "error");
  }
}

boot().catch((error) => banner(error.message, "error"));
