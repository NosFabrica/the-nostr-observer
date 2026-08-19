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
  for (const edition of past) list.append(issue(edition));
  $("archive").hidden = false;
}

// One back issue: when it was, what it said, and how to take it down.
function issue(edition) {
  const li = document.createElement("li");

  const head = document.createElement("div");
  head.className = "issue-head";
  const link = document.createElement("a");
  link.textContent = readableDay(edition.day);
  // A top-level navigation, so no server has to allow us in from here.
  link.href = edition.url || "#";
  link.target = "_blank";
  link.rel = "noopener";
  head.append(link);

  const remove = document.createElement("button");
  remove.className = "link";
  remove.textContent = "Remove";
  remove.onclick = () => removeEdition(edition.day, li);
  head.append(remove);
  li.append(head);

  // WHICH PAPER THIS WAS. The manifest carries the day's lead headline for
  // exactly this: a column of dates says nothing about what was in any of
  // them, and the alternative is fetching every past page to read its <h1>.
  if (edition.headline) {
    const said = document.createElement("p");
    said.className = "issue-headline";
    said.textContent = edition.headline;
    li.append(said);
  }

  if (edition.address) {
    const addr = document.createElement("small");
    addr.textContent = edition.address;
    li.append(addr);
  }
  return li;
}

// "18 August 2026" reads; "2026-08-18" is a sort key. Parsed at local midnight
// so the date cannot slip a day on the way through a timezone.
function readableDay(day) {
  const at = new Date(day + "T00:00:00");
  if (isNaN(at)) return day;
  return at.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" });
}

// Take one edition off the network: a NIP-09 deletion, signed by the reader.
//
// Two calls, like publishing. The server builds the kind 5 so it has something
// to compare the signature against; a flow where this file invents its own
// deletion and the server relays it can check nothing.
async function removeEdition(day, li) {
  // Said in full before it happens. "Delete?" would be a promise we cannot
  // keep: the deletion travels to their relays, and the page itself is a
  // content-addressed file on their media server that anyone holding the hash
  // can still fetch. Content-addressed storage does not really forget.
  const sure = window.confirm(
    `Remove the edition for ${readableDay(day)}?\n\n` +
      "Your relays will be asked to delete it, so it leaves your archive and anyone reading " +
      "your relays stops seeing it. The file stays on your media server, where anyone who " +
      "already has its address can still open it.",
  );
  if (!sure) return;

  const said = document.createElement("p");
  said.className = "aside";
  said.textContent = "Asking your signer…";
  li.append(said);

  const asked = await fetch("/api/archive/" + day + "/remove", { method: "POST" });
  if (!asked.ok) {
    said.className = "lost";
    said.textContent = (await asked.json()).error;
    return;
  }
  const toSign = await asked.json();

  let body = "{}";
  if (state.me.signer === "NIP07") {
    try {
      const signed = await window.nostr.signEvent(JSON.parse(toSign.upload));
      // The server reuses one shape for "here is a signed event"; a removal is
      // one event, so the second slot is empty rather than absent.
      body = JSON.stringify({ upload: JSON.stringify(signed), manifest: "" });
    } catch (e) {
      said.textContent = "Signing was refused, so nothing was removed.";
      return;
    }
  }
  said.textContent = "Telling your relays…";

  const done = await fetch("/api/archive/" + day + "/removed", {
    method: "POST",
    body,
    headers: { "Content-Type": "application/json" },
  });
  const report = await done.json();
  if (!done.ok) {
    said.className = "lost";
    said.textContent = report.error;
    return;
  }
  if (!report.ok) {
    // Every relay refused, so it is still there. Saying "removed" here would
    // be the archive telling them something their relays disagree with.
    said.className = "lost";
    said.textContent = "No relay accepted the removal, so this edition is still published.";
    return;
  }
  li.classList.add("removed");
  // Nothing left to press. A second kind 5 for the same address is a no-op that
  // costs the reader another signer prompt to find that out.
  li.querySelectorAll(".issue-head button").forEach((b) => (b.disabled = true));
  said.textContent = "Removed from your relays. The file itself stays on your media server.";
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
  resume();
}

// A page we are still holding, after a reload.
//
// Only the one case: a run that failed with the edition still in hand. A
// reader who reloads after their servers refused the upload would otherwise
// see a clean desk, with the page they paid for sitting on the server for
// another half hour and no way to ask for it.
async function resume() {
  const res = await fetch("/api/editions/current");
  if (!res.ok) return;
  const current = await res.json();
  if (current.state !== "FAILED" || !current.held || !current.report) return;
  const report = JSON.parse(current.report);
  if (!report.uploads) return;
  state.draft = current.id;
  showLost(report);
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
  drawProgress(status.lines || []);
  if (status.state === "RUNNING") return;

  clearInterval(state.poll);
  if (status.state === "FAILED") {
    $("generate").disabled = false;
    return showFailure(status.error, status);
  }
  if (status.state === "SIGNING") {
    // NO BUTTON HERE. Printing publishes; the page is written, it passed its
    // own checks, and the only thing left is the reader's signer. Asking them
    // to confirm a decision they already made is how a publish gets abandoned
    // half way.
    return publishEdition(status);
  }
  if (status.state === "PUBLISHED" && status.report) {
    $("generate").disabled = false;
    showPublished(JSON.parse(status.report));
  }
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

// `status` is optional: two callers here have only a sentence, because the
// server never answered. When there IS a status, it carries the two things a
// reader whose edition failed its own checks actually needs — which quotes
// could not be verified, and the page itself.
function showFailure(error, status) {
  const panel = $("result");
  panel.hidden = false;
  panel.innerHTML = "";
  const p = document.createElement("p");
  p.className = "waiting";
  p.textContent = error || "It did not work.";
  panel.append(p);
  if (!status) return;

  // Named, not counted. A reader who can see the sentence that failed can tell
  // at a glance whether the writer paraphrased, ran two posts together, or
  // quoted something real that the corpus no longer holds — and that is the
  // difference between "print again" and "this will fail again".
  const summary = status.summary ? JSON.parse(status.summary) : null;
  const bad = (summary && summary.violations) || [];
  if (bad.length) {
    const what = document.createElement("p");
    what.textContent =
      bad.length === 1
        ? "One passage could not be matched to a source event:"
        : bad.length + " passages could not be matched to a source event:";
    panel.append(what);

    const list = document.createElement("ul");
    list.className = "violations";
    for (const v of bad) {
      const li = document.createElement("li");
      const quote = document.createElement("q");
      quote.textContent = v.excerpt;
      li.append(quote);
      const why = document.createElement("small");
      why.textContent = v.detail;
      li.append(why);
      list.append(li);
    }
    panel.append(list);
  }

  // The same offer a refused upload gets, for the same reason: the page exists,
  // it was paid for, and it stops existing shortly.
  if (status.held) {
    const warning = document.createElement("p");
    warning.textContent =
      "The page was written and it is here for about 30 more minutes. It was not published and it will not be — " +
      "save it if you want to read what you paid for.";
    panel.append(warning);

    const save = document.createElement("a");
    save.href = "/api/editions/" + state.draft + "/page";
    save.className = "button";
    save.textContent = "Save this page";
    panel.append(save);
  }
}

function showPublished(report) {
  const panel = $("result");
  panel.hidden = false;
  panel.innerHTML = "";

  const line = document.createElement("p");
  line.className = report.ok ? "ok" : "waiting";
  line.textContent = report.ok
    ? "Published for " + report.day + "."
    : "Uploaded, but no relay accepted it, so nobody can find it yet.";
  panel.append(line);

  if (report.url) {
    const read = document.createElement("a");
    read.href = report.url;
    read.target = "_blank";
    read.rel = "noopener";
    read.className = "button";
    read.textContent = "Read it";
    panel.append(read);
  }

  // Every target, with the server's or relay's own words. "Published" with a
  // silent failure behind it is the case where a reader's paper resolves for us
  // and for nobody else.
  const list = document.createElement("ul");
  for (const row of [...report.uploads, ...report.relays]) {
    const li = document.createElement("li");
    li.dataset.status = row.ok ? "OK" : "BROKEN";
    li.textContent = row.target + " — " + row.detail;
    list.append(li);
  }
  panel.append(list);
  archive();
}

// -------------------------------------------------------------- publishing

async function publishEdition(status) {
  const panel = $("result");
  panel.hidden = false;
  panel.innerHTML = "";
  const note = document.createElement("p");
  panel.append(note);

  let body = "{}";
  if (state.me.signer === "NIP07") {
    // Two prompts, said out loud beforehand. Surprising somebody with a second
    // signer dialog mid-publish is how a publish gets abandoned halfway.
    note.textContent = `Your extension will ask twice: once to authorize the upload to ${status.servers.length} server(s), once for the page itself.`;
    try {
      const upload = await window.nostr.signEvent(JSON.parse(status.upload));
      const manifest = await window.nostr.signEvent(JSON.parse(status.manifest));
      body = JSON.stringify({ upload: JSON.stringify(upload), manifest: JSON.stringify(manifest) });
    } catch (e) {
      $("generate").disabled = false;
      note.className = "waiting";
      note.textContent = "Signing was refused, so nothing was published.";
      return;
    }
    note.textContent = "Uploading and announcing…";
  } else {
    // The signing happens inside the request below, on the server, so this
    // message has to stand for the whole wait -- the reader is looking at their
    // phone, not at this.
    note.textContent = "Your signer will ask twice. Approve both on your device…";
  }

  const res = await fetch("/api/editions/" + state.draft + "/publish", {
    method: "POST",
    body,
    headers: { "Content-Type": "application/json" },
  });
  const report = await res.json();
  $("generate").disabled = false;
  if (!res.ok) {
    // A refused upload is not the same kind of failure as the rest. The others
    // leave the reader where they started; this one leaves them holding a page
    // that is about to stop existing, and the message has to say so.
    if (report.uploads) return showLost(report);
    note.className = "waiting";
    note.textContent = report.error;
    return;
  }
  showPublished(report);
}

// The edition was written and nowhere would keep it.
function showLost(report) {
  const panel = $("result");
  panel.hidden = false;
  panel.innerHTML = "";

  const line = document.createElement("p");
  line.className = "lost";
  line.textContent = report.error;
  panel.append(line);

  // Said plainly, because "it will be swept in 30 minutes" is our word for it
  // and "close this tab and it is gone" is what actually happens to them.
  const warning = document.createElement("p");
  warning.textContent = report.recoverable
    ? `It exists only here, for about ${report.minutes} more minutes. Save it now if you want to keep it — closing this tab loses it.`
    : "It is no longer held here, so it cannot be saved.";
  panel.append(warning);

  if (report.recoverable) {
    const save = document.createElement("a");
    save.href = "/api/editions/" + state.draft + "/page";
    save.className = "button";
    save.textContent = "Save this page";
    panel.append(save);
  }

  const what = document.createElement("p");
  what.className = "aside";
  what.textContent =
    "To publish, add a media server that accepts web pages to your list in your usual Nostr app, " +
    "then print again.";
  panel.append(what);

  // Their servers' own words. "It was refused" does not tell a reader whether
  // to wait, pay, or use a different server -- the sentence does.
  const list = document.createElement("ul");
  for (const row of report.uploads) {
    const li = document.createElement("li");
    li.dataset.status = "BROKEN";
    li.textContent = row.target + " — " + row.detail;
    list.append(li);
  }
  panel.append(list);
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
