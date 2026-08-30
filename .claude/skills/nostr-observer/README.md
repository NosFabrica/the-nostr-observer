# The Nostr Observer, as a Claude Code skill

A taster of [the Nostr Observer](../README.md) that runs entirely inside your
own Claude Code: it reads the last 24 hours of Nostr through your web of trust,
writes a newspaper front page, checks its own work, and hands you the page.

Nothing here talks to a server of ours. There is no account, no API key and no
credential of any kind — your Claude Code makes the model call, on your own
plan, the same way it does for everything else you use it for.

## Install

Node 22 or newer is required (the scripts use the built-in `WebSocket`, so
there is nothing to `npm install`).

Two ways in. Open the repository in Claude Code and the skill is already
loaded — it lives in this repo's own `.claude/skills/`, which is where Claude
Code looks for project skills:

```bash
git clone https://github.com/NosFabrica/the-nostr-observer
cd the-nostr-observer && claude
```

Or install it globally, so it works in any directory:

```bash
git clone https://github.com/NosFabrica/the-nostr-observer
mkdir -p ~/.claude/skills
cp -r the-nostr-observer/.claude/skills/nostr-observer ~/.claude/skills/
```

Either way it is Claude Code, which is the terminal, the desktop app, or
[claude.ai/code](https://claude.ai/code) in a browser — no terminal required.
Plain Claude chat will not do: its sandbox reaches an allowlist that does not
include the relay, and the ranked query is a websocket with no HTTP form to
fall back to.

Then start `claude` **in whatever directory you want your paper to land in**
and ask for it:

```
> print my Nostr Observer
```

It will ask which npub to read for, check that your lens actually resolves, and
stop with a specific remedy if it does not. If it prints a paper, you get two
things: an `observer-<date>-<code>.html` file in `editions/` of that directory, and an
artifact link.

Everything runs locally. You are not signing in to anything of ours, and no
key of yours goes anywhere — the skill reads public relay data and writes a
file.

## What it does

| Step | |
|---|---|
| 1 | Asks for your npub — it becomes the `observer:<pubkey>` token the relay ranks by |
| 2 | `readiness.mjs` — four links, first unmet one wins, with the fix for that one |
| 3 | `corpus.mjs` — fourteen desks plus an unranked control run, over a fixed 24-hour window |
| 4 | Writes the page against `reference/editorial.md` and `reference/house.css` |
| 5 | `validate.mjs` — every quote verbatim, every picture from the shortlist, no link to the open web |
| 6 | `embed.mjs` — a separate artifact copy with the pictures inlined, since the artifact viewer blocks remote hosts |
| 7 | Saves the HTML and publishes the embedded copy as an artifact |

## If it says NOT READY

That is the skill working. The relay ranks through a lens built from NIP-85
trust assertions, and `observer:<pk> sort:rank` with an unresolvable observer
does not error — it silently becomes the anonymous global ranking. So the check
is a gate: a paper without a lens looks right and is not the product.

The most common answer is that you have no `kind 10040` naming a `30382:rank`
service. Get a lens minted at [brainstorm.world](https://brainstorm.world).

## Testing

Four layers, and only the first two can run in CI.

**1. Unit and boundary tests — no network, under a second.**

```bash
node --test ".claude/skills/nostr-observer/test/*.test.mjs"
```

75 tests. bech32 against the NIP-19 worked example, the readiness chain in
every state it can reach, query construction, the relay auth gate, socket
sharing, the digest budget, the artifact embed step, and the boundary from
both sides.

**2. The relay client against a relay that misbehaves on purpose.**

`test/fakerelay.mjs` is a dependency-free WebSocket server that reproduces the
hazards recorded in `AGENTS.md` — the AUTH challenge sent before an answer, a
`NOTICE` mid-stream, a subscription that says nothing, a `CLOSED` with a
reason. Each of those fails *silently* against a real relay: the symptom is an
empty list, which looks exactly like a quiet day. Reproducing them locally is
the only way they stay caught.

**3. The golden edition — does the boundary leave a good page alone?**

The adversarial tests answer "does it stop the bad things". This answers the
likelier way to ship something broken. It takes the 56 KB prototype broadsheet
from `generator/src/test/resources/`, derives a corpus from what the page
itself cites, and asserts that `resolve` + `validate` return it untouched and
clean. A checker that quietly rejects a real broadsheet passes every
adversarial test and prints nothing every morning. (Skipped automatically if
you installed the skill on its own, without the repository.)

**4. Live, against the relay — needs a reader with a working lens.**

```bash
mkdir -p editions
node scripts/readiness.mjs <npub>              # exit 0 means ready
node scripts/corpus.mjs <npub> --out editions/corpus.json > editions/digest.md
```

This is the layer CI cannot have, because the workflow's rule is that nothing
in it talks to a relay. Run it by hand against an npub whose `kind 10040` names
a `30382:rank` service with cards on the search relay. Two things to look at:
the **Instrument** line in the digest — a low overlap between the ranked notes
and the unranked control is the product working — and whether the desks
returned anything at all.

Useful check with a *broken* lens, which is easier to find: the desks should
return nothing while the control run returns hundreds. That is the trust floor
biting. It does not make the readiness gate redundant — the readiness probe
deliberately sends no floor, precisely so it can still see the silent
degradation to anonymous ranking.

**And then the part no test covers.** Whether the paper is any *good* is a
human read. Run the whole skill, open the file, and ask whether a person would
want it tomorrow. That judgement is the actual product and there is no
assertion for it.

## What it does not do

Put a paper on the public web — that is the sibling skill `observer-pages`.
This prints today's paper into `editions/` and stops. It also does not
publish to your Blossom servers as an nsite, carry the masthead forward from
yesterday, or run on a schedule. Those are the full Observer.

Also: the artifact viewer blocks remote images, and this paper hotlinks art
where its authors published it rather than re-hosting anyone's photographs.
The artifact therefore gets its own copy, built by `embed.mjs`, with each
shortlist picture inlined as a `data:` URI; the saved HTML file stays
hotlinked. A picture the embed step cannot fetch shows in the artifact as its
caption and alt, and the run says which.

## Editing it

`reference/editorial.md` and `reference/house.css` are **generated**. Edit
`generator/src/main/resources/system-prompt.md` or `house.css` at the repository
root and run `tools/sync-skill.sh`.
