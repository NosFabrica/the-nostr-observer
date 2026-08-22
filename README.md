# The Nostr Observer

Your own daily newspaper, printed from your corner of Nostr.

Sign in, and once a day the Observer reads everything the people you trust have
posted in the last 24 hours and lays it out as a front page — a lead story,
photographs, a books-and-gardens column, whatever the day actually contained.
It is written fresh each morning, so a quiet Tuesday looks like a quiet Tuesday
and a big day gets a big headline.

It is **your** paper in a literal sense. The stories are chosen by your own web
of trust, not by an algorithm we tuned, and the finished page is published to
your own media servers under your own key. We print it; you own it.

## What you need

- **A Nostr account**, with a signer — a browser extension or a remote signer app.
- **A web-of-trust lens.** This is what makes the paper yours rather than a
  firehose. If you do not have one yet, the Observer will offer to set one up
  and tell you when it is ready.
- **A media server** (Blossom), if you want to publish and share your editions.
  You can read your own paper without one.

## What it costs you

Nothing but the storage on your own media server — about the size of a couple of
emails per edition. Photographs stay where their authors put them; the Observer
only points at them.

## Sharing

Every edition you publish gets a link you can hand to anyone. They do not need an
account to read it. Editions you do not publish stay yours and are visible to
nobody else.

## Status

Early, but it runs. You can sign in with an extension or a remote signer, and the
Observer will read your web of trust, print a page, show it to you privately and
publish it to your media servers when you say so.

Two things are not finished. Setting up a web-of-trust lens for a brand new
reader still needs a person at our end; until yours is ready the Observer tells
you exactly what it is waiting on rather than printing a paper chosen some other
way. And editions are made on demand — a paper that arrives every morning without
you asking is next.

## Try it in Claude Code

There is a taster that runs entirely on your own machine, with no account here
at all: a Claude Code skill that reads your web of trust, prints a front page
and hands it to you as an artifact. It uses your own Claude Code — no API key,
nothing to sign up for.

Open this repository in Claude Code — the terminal, the desktop app, or
[claude.ai/code](https://claude.ai/code) in a browser — and just ask:

```
print my Nostr Observer
```

The skill lives at [`.claude/skills/nostr-observer`](.claude/skills/nostr-observer),
so a checkout of this repo already has it; there is nothing to install. To have
it everywhere instead of only here, copy it into your own skills directory:

```bash
cp -r .claude/skills/nostr-observer ~/.claude/skills/
```

It prints today's paper once; it does not publish to your media servers, keep an
archive, or arrive every morning.

The design is written up in [`docs/PLAN.md`](docs/PLAN.md).

---

Built by [NosFabrica](https://github.com/NosFabrica) on
[vespa-relay](https://github.com/NosFabrica/vespa-relay).
