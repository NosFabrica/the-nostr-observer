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

/**
 * Every paper they have already published, off their own relays.
 *
 * Not from a database of ours — there isn't one. Each edition is its own nsite
 * under the day it was printed, so this list is theirs whether we are running
 * or not, and it is the same list anybody else reading their relays would see.
 */
async function archive() {
  const res = await fetch("/api/archive");
  if (!res.ok) return;
  const past = await res.json();
  if (!past.length) return;

  const list = $("issues");
  list.innerHTML = "";
  for (const edition of past) {
    const li = document.createElement("li");
    const link = document.createElement("a");
    link.textContent = edition.day;
    // A top-level navigation, so no server has to allow us in from here.
    link.href = edition.url || "#";
    link.target = "_blank";
    link.rel = "noopener";
    li.append(link);
    if (edition.address) {
      const addr = document.createElement("small");
      addr.textContent = edition.address;
      li.append(addr);
    }
    list.append(li);
  }
  $("archive").hidden = false;
}

function arrive(who) {
  state.me = who;
  // A name, or an npub if they have not published one. The server sends one
  // string precisely so there is nothing here that could print a key.
  $("me").textContent = who.name;
  $("signin").hidden = true;
  $("desk").hidden = false;
  readiness();
  archive();
}

async function signOut() {
  await fetch("/api/session/end", { method: "POST" });
  location.reload();
}

// -------------------------------------------------------------- readiness

async function readiness() {
  const panel = $("readiness");
  panel.textContent = "Checking…";
  const res = await fetch("/api/readiness");
  if (!res.ok) return (panel.textContent = "");
  const pre = await res.json();
  panel.innerHTML = "";

  // Sign-in showed their npub because it had not been on the network yet. Now
  // it has, so they get their name.
  if (pre.name) $("me").textContent = pre.name;

  // ONE SENTENCE, then the button. The chains used to be drawn in full,
  // always, with their internal link names showing -- `relayList — declared=0`,
  // `scoreList`, `uploadConsent`. That is a debug view of a state machine, and
  // it was the first thing a reader saw after signing in. What they need is
  // whether they can print, and if not, the one thing to go and do.
  const ready = pre.lens.ranks;
  const line = document.createElement("p");
  line.className = ready ? "ok" : "waiting";
  line.textContent = ready ? "Ready to print." : pre.lens.explanation;
  panel.append(line);

  // Storage is only worth a line when it is the thing standing in the way. A
  // reader who can read today's paper does not need to hear about media
  // servers until they try to publish it.
  if (ready && !pre.storage.ranks) {
    const storage = document.createElement("p");
    storage.className = "note";
    storage.textContent = pre.storage.explanation;
    panel.append(storage);
  }

  panel.append(details(pre));
  $("generate").disabled = !ready;
}

/** The chains, for anyone who wants them. Closed by default, and it stays closed. */
function details(pre) {
  const box = document.createElement("details");
  const summary = document.createElement("summary");
  summary.textContent = "What we checked";
  box.append(summary);
  drawChain(box, "Your web of trust", pre.lens);
  drawChain(box, "Somewhere to publish", pre.storage);
  return box;
}

// The internal names of the links, said in words. The keys are how the code
// talks about a state machine; they are not how a person is told what is
// missing from their account.
const LINKS = {
  relayList: "your relay list",
  scoreList: "your chosen scoring service",
  scores: "your trust scores",
  ranked: "ranking your feed",
  posts: "your own posts",
  blossomServers: "your media servers",
  uploadConsent: "permission to upload",
};

function drawChain(box, title, verdict) {
  const head = document.createElement("p");
  head.className = "note";
  head.textContent = title + " — " + verdict.explanation;
  box.append(head);

  const chain = document.createElement("ul");
  chain.className = "chain";
  for (const link of verdict.chain) {
    const li = document.createElement("li");
    li.dataset.status = link.status;
    li.textContent = LINKS[link.key] || link.key;
    if (link.detail) {
      const small = document.createElement("small");
      small.textContent = link.detail;
      li.append(small);
    }
    chain.append(li);
  }
  box.append(chain);
}

// ------------------------------------------------------------- generating

async function generate() {
  $("generate").disabled = true;
  $("result").hidden = true;
  $("progress").innerHTML = "";
  // The one fact only the browser has. A published edition carries no script,
  // so it cannot work out the reader's clock when they open it -- the zone has
  // to travel with the request and be printed into the page.
  const res = await fetch("/api/editions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ timezone: Intl.DateTimeFormat().resolvedOptions().timeZone }),
  });
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
  stat.textContent = `${summary.events} posts from ${summary.voices} people you trust.`;
  panel.append(stat);

  // The comparison is the product's whole argument, and it was the lead
  // sentence -- two clauses of methodology before the reader had seen their
  // paper. It is still here, said in one line, underneath.
  const against = document.createElement("p");
  against.className = "note";
  against.textContent =
    `Reading the same window without your web of trust returns ${summary.control} posts. ` +
    `${summary.overlap} of them made this page.`;
  panel.append(against);

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
