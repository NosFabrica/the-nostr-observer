// The whole client. No build step, no framework, no dependencies -- the page
// has three jobs (sign in, poll a job, hand two events to a signer) and every
// one of them is a fetch and a bit of DOM.

const $ = (id) => document.getElementById(id);
const state = { me: null, draft: null, poll: null, misses: 0 };

// ---------------------------------------------------------------- sign in

/**
 * NIP-98: a signed event describing THIS request.
 *
 * The `u` and `method` tags are what the server checks against the request it
 * actually received, so a signature captured from one endpoint cannot be
 * replayed at another. Same shape for an extension and a remote signer, which
 * is why sign-in has one code path here and one verifier there.
 */
async function nip98(url, method, body) {
  const event = {
    kind: 27235,
    created_at: Math.floor(Date.now() / 1000),
    tags: [
      ["u", url],
      ["method", method],
    ],
    content: "",
  };
  if (body) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(body));
    event.tags.push(["payload", [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("")]);
  }
  const signed = await window.nostr.signEvent(event);
  return "Nostr " + btoa(JSON.stringify(signed));
}

async function signInWithExtension() {
  if (!(window.nostr && window.nostr.signEvent)) {
    return note("No Nostr extension found. Use a signer app and paste its bunker:// address instead.");
  }
  const url = location.origin + "/api/session";
  const body = JSON.stringify({ signer: "NIP07" });
  note("Waiting for your extension…");
  try {
    const auth = await nip98(url, "POST", body);
    const res = await fetch(url, { method: "POST", body, headers: { Authorization: auth, "Content-Type": "application/json" } });
    const data = await res.json();
    if (!res.ok) return note(data.error);
    arrive(data);
  } catch (e) {
    note(e.message || "your extension refused");
  }
}

async function signInWithBunker() {
  const uri = $("bunker").value.trim();
  if (!uri.startsWith("bunker://")) return note("That does not look like a bunker:// address.");
  // Deliberately blunt about the wait: connecting means a round trip to the
  // reader's signer over relays, and it can take a while on a phone.
  note("Connecting to your signer. Approve the request on your device…");
  const res = await fetch("/api/session/bunker", {
    method: "POST",
    body: JSON.stringify({ uri }),
    headers: { "Content-Type": "application/json" },
  });
  const data = await res.json();
  if (!res.ok) return note(data.error);
  arrive(data);
}

function arrive(who) {
  state.me = who;
  $("me").textContent = who.pubkey.slice(0, 8) + "… (" + who.signer + ")";
  $("signin").hidden = true;
  $("desk").hidden = false;
  readiness();
}

async function signOut() {
  await fetch("/api/session/end", { method: "POST" });
  location.reload();
}

// -------------------------------------------------------------- readiness

async function readiness() {
  const panel = $("readiness");
  panel.textContent = "Checking your lens…";
  const res = await fetch("/api/readiness");
  if (!res.ok) return (panel.textContent = "");
  const pre = await res.json();
  panel.innerHTML = "";

  // Two chains, drawn separately, because they fail independently: no media
  // server is not a broken lens, and a reader with one and not the other should
  // be able to see which.
  drawChain(panel, pre.lens);
  drawChain(panel, pre.storage);

  // No lens, no ranked paper. The button says so rather than producing
  // something built a different way and calling it the reader's paper.
  $("generate").disabled = !pre.lens.ranks;
  if (!pre.lens.ranks) {
    const waiting = document.createElement("p");
    waiting.className = "waiting";
    waiting.textContent = "We will tell you as soon as your lens is ready.";
    panel.append(waiting);
  }
}

function drawChain(panel, verdict) {
  const line = document.createElement("p");
  line.className = verdict.ranks ? "ok" : "waiting";
  line.textContent = verdict.explanation;
  panel.append(line);

  const chain = document.createElement("ul");
  chain.className = "chain";
  for (const link of verdict.chain) {
    const li = document.createElement("li");
    li.dataset.status = link.status;
    li.textContent = link.key + (link.detail ? " — " + link.detail : "");
    chain.append(li);
  }
  panel.append(chain);
}

// ------------------------------------------------------------- generating

async function generate() {
  $("generate").disabled = true;
  $("result").hidden = true;
  $("progress").innerHTML = "";
  const res = await fetch("/api/editions", { method: "POST" });
  const data = await res.json();
  if (!res.ok) {
    $("generate").disabled = false;
    return note(data.error);
  }
  state.draft = data.draft;
  state.misses = 0;
  state.poll = setInterval(pollDraft, 2000);
  pollDraft();
}

async function pollDraft() {
  // A dropped connection must not silently poll forever. Give up after a run of
  // failures and say so, rather than leaving a spinner that means nothing.
  let res;
  try {
    res = await fetch("/api/editions/" + state.draft);
  } catch (e) {
    if (++state.misses < 5) return;
    clearInterval(state.poll);
    $("generate").disabled = false;
    return showFailure("Lost contact with the server. Your edition may still be running -- reload to check.");
  }
  if (!res.ok) return;
  state.misses = 0;
  const status = await res.json();
  drawProgress(JSON.parse(status.progress || "[]"));
  if (status.state === "RUNNING") return;

  clearInterval(state.poll);
  $("generate").disabled = false;
  if (status.state === "FAILED") return showFailure(status.error);
  showEdition(JSON.parse(status.summary || "{}"));
}

function drawProgress(lines) {
  const list = $("progress");
  list.innerHTML = "";
  for (const line of lines) {
    const li = document.createElement("li");
    li.textContent = line.text;
    if (line.detail) {
      const small = document.createElement("small");
      small.textContent = line.detail;
      li.append(small);
    }
    list.append(li);
  }
}

function showFailure(error) {
  const panel = $("result");
  panel.hidden = false;
  panel.innerHTML = "";
  const p = document.createElement("p");
  p.className = "waiting";
  p.textContent = error || "It did not work.";
  panel.append(p);
}

function showEdition(summary) {
  const panel = $("result");
  panel.hidden = false;
  panel.innerHTML = "";

  const stat = document.createElement("p");
  stat.textContent =
    `${summary.events} posts from ${summary.voices} people, through your web of trust. ` +
    `Of the ${summary.control} posts an unranked read returned for the same window, ${summary.overlap} made it in.`;
  panel.append(stat);

  const preview = document.createElement("a");
  preview.href = "/draft/" + state.draft;
  preview.target = "_blank";
  preview.rel = "noopener";
  preview.className = "button";
  preview.textContent = "Read it";
  panel.append(preview);

  if (!summary.publishable) {
    // The button is not merely disabled: an edition that failed its own checks
    // is not offered at all, and the reason is printed.
    const bad = document.createElement("div");
    bad.className = "waiting";
    bad.textContent = "This edition failed its own checks, so it is not offered for publication:";
    const why = document.createElement("ul");
    for (const v of summary.violations || []) {
      const li = document.createElement("li");
      li.textContent = v;
      why.append(li);
    }
    bad.append(why);
    panel.append(bad);
    return;
  }

  const publish = document.createElement("button");
  publish.textContent = "Publish to my servers";
  publish.onclick = () => publishEdition(publish);
  panel.append(publish);
}

// -------------------------------------------------------------- publishing

async function publishEdition(button) {
  button.disabled = true;
  const status = document.createElement("p");
  button.after(status);
  status.textContent = "Working out where your paper goes…";

  const prep = await fetch("/api/editions/" + state.draft + "/prepare", { method: "POST" });
  const plan = await prep.json();
  if (!prep.ok) {
    button.disabled = false;
    status.className = "waiting";
    return (status.textContent = plan.error);
  }

  let body = "{}";
  if (state.me.signer === "NIP07") {
    // Two prompts, said out loud beforehand. Surprising somebody with a second
    // signer dialog mid-publish is how a publish gets abandoned halfway.
    status.textContent = `Your extension will ask twice: once to authorize the upload to ${plan.servers.length} server(s), once for the manifest.`;
    try {
      const upload = await window.nostr.signEvent(JSON.parse(plan.upload));
      const manifest = await window.nostr.signEvent(JSON.parse(plan.manifest));
      body = JSON.stringify({ upload: JSON.stringify(upload), manifest: JSON.stringify(manifest) });
    } catch (e) {
      button.disabled = false;
      status.className = "waiting";
      return (status.textContent = "Signing was refused.");
    }
    status.textContent = "Uploading and announcing…";
  } else {
    // The signing happens inside the request below, on the server, so this
    // message has to stand for the whole wait -- the reader is looking at
    // their phone, not at this.
    status.textContent = "Your signer will ask twice. Approve both on your device…";
  }

  const res = await fetch("/api/editions/" + state.draft + "/publish", {
    method: "POST",
    body,
    headers: { "Content-Type": "application/json" },
  });
  const report = await res.json();
  if (!res.ok) {
    button.disabled = false;
    status.className = "waiting";
    return (status.textContent = report.error);
  }
  drawReport(status, report);
}

function drawReport(status, report) {
  // A second publish must not leave the first one's list sitting underneath it.
  status.nextElementSibling?.remove();

  status.textContent = report.ok
    ? `Published as ${report.naddr} for ${report.day}.`
    : "Uploaded, but no relay accepted the manifest, so nobody can find it yet.";
  status.className = report.ok ? "ok" : "waiting";

  // Every target, with the server's or relay's own words. "Published" with a
  // silent failure behind it is the case where a reader's paper resolves for
  // us and for nobody else.
  const list = document.createElement("ul");
  for (const row of [...report.uploads, ...report.relays]) {
    const li = document.createElement("li");
    li.dataset.status = row.ok ? "OK" : "BROKEN";
    li.textContent = row.target + " — " + row.detail;
    list.append(li);
  }
  status.after(list);
}

// ------------------------------------------------------------------- boot

function note(text) {
  $("signin-note").textContent = text;
}

$("nip07").onclick = signInWithExtension;
$("nip46").onclick = signInWithBunker;
$("signout").onclick = signOut;
$("generate").onclick = generate;

fetch("/api/session")
  .then((r) => (r.ok ? r.json() : null))
  .then((who) => {
    if (who) arrive(who);
    else $("signin").hidden = false;
  });
