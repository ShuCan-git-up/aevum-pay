const state = { session: null, cfg: null };

const $ = (id) => document.getElementById(id);

function banner(text, kind) {
  const node = $("banner");
  node.className = "banner " + (kind || "");
  node.textContent = text;
  node.hidden = !text;
}

async function boot() {
  state.cfg = await Aevum.config();
  $("network").textContent = state.cfg.networkName + " · " + state.cfg.chainId;
  $("contract").textContent = state.cfg.contractAddress || "Not configured";
  $("operator").textContent = state.cfg.operatorAddress || "No operator key is configured on the backend";
  if (!state.cfg.contractAddress) {
    banner("Contract address is not configured. Set CONTRACT_ADDRESS and refresh.", "error");
  }
  const due = new Date(Date.now() + 7 * 86400000);
  const local = new Date(due.getTime() - due.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  $("due").value = local;
  const guard = (fn) => () => Promise.resolve(fn()).catch((error) => banner(Aevum.explain(error), "error"));
  $("connect").onclick = guard(onConnect);
  $("create").onclick = guard(onCreate);
  $("authorize").onclick = guard(onAuthorize);
  $("register").onclick = guard(onRegister);
  $("pause").onclick = guard(() => ownerCall("pause"));
  $("unpause").onclick = guard(() => ownerCall("unpause"));
  $("set-fee").onclick = guard(onSetFee);
  refreshSync();
  setInterval(refreshSync, 4000);
}

async function onConnect() {
  try {
    banner("");
    state.session = await Aevum.connect();
    $("account").textContent = state.session.address;
    $("connect").textContent = "Connected";
    $("wallet-tools").hidden = false;
    await refreshOwner();
    await refreshInvoices();
    if (!state.polling) {
      state.polling = setInterval(() => refreshInvoices().catch((error) => console.warn(error)), 4000);
    }
  } catch (error) {
    banner(Aevum.explain(error), "error");
  }
}

async function refreshOwner() {
  try {
    const { payment } = await Aevum.contracts(state.session.browser);
    const owner = (await payment.owner()).toLowerCase();
    $("owner-tools").hidden = owner !== state.session.address.toLowerCase();
    const operator = state.cfg.operatorAddress;
    $("operator").textContent = operator || "No operator key is configured on the backend";
  } catch (error) {
    console.warn(error);
  }
}

async function refreshSync() {
  try {
    const sync = await fetch("/api/v1/public/sync").then((response) => response.json());
    const lag = sync.lag == null ? "—" : sync.lag;
    const unit = Number(sync.lag) === 1 ? "block" : "blocks";
    $("sync").textContent = "Sync cursor " + (sync.cursor ?? "—") + " · " + lag + " " + unit + " behind";
  } catch (_) {
    $("sync").textContent = "Sync status unavailable";
  }
}

async function refreshInvoices() {
  if (!state.session) return;
  const url = "/api/v1/public/invoices?merchant=" + state.session.address;
  const rows = await fetch(url).then((response) => response.json());
  const body = $("invoices");
  body.innerHTML = "";
  if (!rows.length) {
    body.innerHTML = "<tr><td colspan='5'>No on-chain invoices yet. After you create one, the indexer usually confirms it within a few seconds.</td></tr>";
    return;
  }
  for (const row of rows) {
    const tr = document.createElement("tr");
    const id = row.chainInvoiceId ?? "—";
    const link = row.chainInvoiceId ? Aevum.payPath(row.chainInvoiceId) : "";
    tr.innerHTML = `
      <td class="mono">${id}<div class="hint">${escapeHtml(row.orderId || "")}</div></td>
      <td><span class="status ${row.status}">${row.status}</span></td>
      <td>${Aevum.formatAmount(row.paidAmount, state.cfg.tokenDecimals)} / ${Aevum.formatAmount(row.amount, state.cfg.tokenDecimals)}</td>
      <td class="mono">${Aevum.short(row.payer)}</td>
      <td></td>`;
    const cell = tr.lastElementChild;
    if (link) {
      cell.append(button("Pay link", () => copyLink(link)));
      cell.append(button("QR code", () => showQr(location.origin + link)));
      if (canCancel(row)) cell.append(button("Cancel", () => onCancel(row.chainInvoiceId)));
      if (canRefund(row)) cell.append(button("Refund", () => onRefund(row)));
    }
    body.append(tr);
  }
}

function canCancel(row) {
  return (row.status === "PENDING" || row.status === "EXPIRED" || row.status === "CREATING")
    && BigInt(row.paidAmount) === 0n;
}

function canRefund(row) {
  return BigInt(row.paidAmount) > BigInt(row.refundedAmount)
    && (row.status === "PAID" || row.status === "PARTIALLY_PAID" || row.status === "EXPIRED" || row.status === "REFUNDED");
}

function button(label, action) {
  const node = document.createElement("button");
  node.type = "button";
  node.className = "secondary";
  node.textContent = label;
  node.style.marginRight = "6px";
  node.onclick = () => action().catch((error) => banner(Aevum.explain(error), "error"));
  return node;
}

async function onCreate() {
  requireWallet();
  const { payment, iface, cfg } = await Aevum.contracts(state.session.signer);
  const amount = Aevum.parseAmount($("amount").value, cfg.tokenDecimals);
  if (amount <= 0n) throw new Error("Amount must be greater than 0");
  const dueAt = Math.floor(new Date($("due").value).getTime() / 1000);
  const payer = $("payer").value.trim();
  const orderId = $("order").value.trim();
  const orderRef = orderId ? ethers.keccak256(ethers.toUtf8Bytes(orderId)) : ethers.ZeroHash;
  banner("Waiting for you to sign the invoice…");
  const tx = await payment.createInvoice(payer || ethers.ZeroAddress, amount, dueAt, $("partial").checked, orderRef);
  const receipt = await tx.wait();
  let id = null;
  for (const log of receipt.logs) {
    try {
      const parsed = iface.parseLog(log);
      if (parsed && parsed.name === "InvoiceCreated") id = parsed.args.id.toString();
    } catch (_) { /* log from another contract */ }
  }
  const href = id ? location.origin + Aevum.payPath(id) : "";
  banner("On chain" + (id ? ", invoice #" + id + " " + href : "") + ". tx " + tx.hash, "ok");
  await refreshInvoices();
}

async function onAuthorize() {
  requireWallet();
  if (!state.cfg.operatorAddress) throw new Error("The backend has not published an operator address");
  const { payment } = await Aevum.contracts(state.session.signer);
  banner("Authorizing the operator only allows it to create invoices and cancel unpaid ones. It cannot touch funds.");
  const tx = await payment.setOperator(state.cfg.operatorAddress, true);
  await tx.wait();
  banner("Operator authorized: " + state.cfg.operatorAddress, "ok");
}

async function onRegister() {
  requireWallet();
  const address = state.session.address;
  const nonce = await fetch("/api/v1/auth/nonce?address=" + address).then((response) => response.json());
  const signature = await state.session.signer.signMessage(nonce.message);
  const response = await fetch("/api/v1/merchants", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: $("shop").value.trim() || "Merchant",
      walletAddress: address,
      nonce: nonce.nonce,
      signature,
      webhookUrl: $("webhook").value.trim()
    })
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || "Registration failed");
  $("secret").textContent = "API key (shown only once)\n" + body.apiKey + "\n\nWebhook secret\n" + body.webhookSecret;
  $("secret-dialog").showModal();
}

async function onCancel(id) {
  const { payment } = await Aevum.contracts(state.session.signer);
  const tx = await payment.cancelInvoice(id);
  await tx.wait();
  banner("Cancel transaction sent " + tx.hash, "ok");
  await refreshInvoices();
}

async function onRefund(row) {
  const human = window.prompt("Refund amount (tUSD8)", Aevum.formatAmount(
    BigInt(row.paidAmount) - BigInt(row.refundedAmount), state.cfg.tokenDecimals));
  if (!human) return;
  const amount = Aevum.parseAmount(human, state.cfg.tokenDecimals);
  const { payment, token, cfg } = await Aevum.contracts(state.session.signer);
  const allowance = await token.allowance(state.session.address, cfg.contractAddress);
  if (allowance < amount) {
    banner("Approve tUSD8 first, then refund.");
    await (await token.approve(cfg.contractAddress, amount)).wait();
  }
  const tx = await payment.refund(row.chainInvoiceId, amount);
  await tx.wait();
  banner("Refund sent " + tx.hash, "ok");
  await refreshInvoices();
}

async function ownerCall(method) {
  const { payment } = await Aevum.contracts(state.session.signer);
  const tx = await payment[method]();
  await tx.wait();
  banner(method + " " + tx.hash, "ok");
}

async function onSetFee() {
  const bps = Number($("fee-bps").value);
  const recipient = $("fee-to").value.trim();
  const { payment } = await Aevum.contracts(state.session.signer);
  const tx = await payment.setFee(bps, recipient);
  await tx.wait();
  banner("Fee updated (capped at 1%). " + tx.hash, "ok");
}

function copyLink(path) {
  const url = location.origin + path;
  navigator.clipboard.writeText(url);
  banner("Copied " + url, "ok");
  return Promise.resolve();
}

function showQr(url) {
  const box = $("qr");
  box.innerHTML = "";
  new QRCode(box, { text: url, width: 180, height: 180 });
  $("qr-url").textContent = url;
  $("qr-dialog").showModal();
}

function requireWallet() {
  if (!state.session) throw new Error("Connect a wallet first");
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[char]));
}

boot().catch((error) => banner(error.message, "error"));
