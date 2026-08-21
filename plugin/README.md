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

Copy the skill into your Claude Code skills directory:

```bash
git clone https://github.com/NosFabrica/the-nostr-observer
cp -r the-nostr-observer/plugin/skills/nostr-observer ~/.claude/skills/
```

Then ask for it:

```
> print my Nostr Observer
```

It will ask which npub to read for, check that your lens actually resolves, and
stop with a specific remedy if it does not.

## What it does

| Step | |
|---|---|
| 1 | Asks for your npub — it becomes the `observer:<pubkey>` token the relay ranks by |
| 2 | `readiness.mjs` — four links, first unmet one wins, with the fix for that one |
| 3 | `corpus.mjs` — fourteen desks plus an unranked control run, over a fixed 24-hour window |
| 4 | Writes the page against `reference/editorial.md` and `reference/house.css` |
| 5 | `validate.mjs` — every quote verbatim, every picture from the shortlist, no link to the open web |
| 6 | Saves the HTML and publishes it as an artifact |

## If it says NOT READY

That is the skill working. The relay ranks through a lens built from NIP-85
trust assertions, and `observer:<pk> sort:rank` with an unresolvable observer
does not error — it silently becomes the anonymous global ranking. So the check
is a gate: a paper without a lens looks right and is not the product.

The most common answer is that you have no `kind 10040` naming a `30382:rank`
service. Get a lens minted at [brainstorm.world](https://brainstorm.world).

## What it does not do

Publish to your Blossom servers as an nsite, keep an archive, carry the
masthead forward from yesterday, or run on a schedule. Those are the full
Observer. This prints today's paper once.

Also: the artifact viewer blocks remote images, and this paper hotlinks art
where its authors published it rather than re-hosting anyone's photographs. In
the artifact, pictures read as their captions; open the saved HTML file to see
the art.

## Editing it

`reference/editorial.md` and `reference/house.css` are **generated**. Edit
`generator/src/main/resources/system-prompt.md` or `house.css` at the repository
root and run `tools/sync-skill.sh`.
