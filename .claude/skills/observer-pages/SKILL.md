---
name: observer-pages
description: Deploy selected Nostr Observer editions to Vercel as a static site with an index of the published papers. Use when the user wants to publish today's paper, put an edition online, deploy the Observer to Vercel, update the public archive, take a paper live, or add a paper to the public shelf. Never deploy an edition the user did not name, and never upload the editions/ folder.
---

# Observer pages

A public shelf for papers that already exist on disk. This does not print a
newspaper. It copies **named** editions into `dist/` and deploys that folder
to Vercel. No extra GitHub repository. The print skill writes every run into
`editions/` — including `corpus.json`, which must never go online.

Hobby Vercel is free. One login on this machine, then `npx vercel deploy`.

---

## Step 0 — Find the scripts, once

They live in `scripts/` beside this file. Claude Code runs from the reader's
working directory, so a relative `node scripts/…` will fail. Resolve the
absolute path now and write it literally into every command afterwards.

```bash
find . ~/.claude -name SKILL.md -path '*observer-pages*' 2>/dev/null | head -5
```

Take the directory containing that `SKILL.md` as the prefix for every script
call below.

If `editions/` is missing and `dist/` still holds `observer-*.html` **and**
`corpus.json`, that is the old layout. Stop and say so: rename it with
`mv dist editions && mkdir dist` before continuing. Do not deploy `dist/`
until that split is done.

---

## Step 1 — Show the shelf, then wait

```bash
node <skill>/scripts/site.mjs list
```

Two lists: everything in `editions/` (printed), and everything in `dist/`
(already public). **Do not add anything until the reader names it.**

If they said "today's paper", "the 27th", a date, or an edition code, that is a
name. If they said "deploy the observer" with no edition, ask which ones.

Two papers on the same day is normal. If the selector is a date and two match,
tell them both titles and confirm before adding, unless they already said "both"
or "all of the 25th".

**Never default to publishing every paper in `editions/`.** That folder is
the private archive. Choosing is the point of this skill.

---

## Step 2 — Stage only what they named

```bash
node <skill>/scripts/site.mjs add <edition...>
```

Selectors: `observer-2026-08-27-1EAF35.html`, `1EAF35`, `2026-08-27`, or
`today`. To take a paper down:

```bash
node <skill>/scripts/site.mjs remove <edition...>
```

Then:

```bash
node <skill>/scripts/site.mjs check
```

**Exit 0 or do not deploy.** A non-zero check means `dist/` contains something
that is not an edition — usually `corpus.json`. `favicon.svg` is site
furniture and is allowed. Fix junk before Vercel sees the folder. Do not
pass `--force`. Do not point Vercel at `editions/`.

Local preview, optional:

```bash
npx serve dist
```

---

## Step 3 — Deploy the dist folder only

```bash
npx vercel whoami
```

If that fails: tell them to run `npx vercel login` in the terminal, and **end
the turn**. Do not invent a token. Do not paste credentials.

When whoami succeeds:

```bash
npx vercel project add thenostrobserver
npx vercel deploy dist --prod --yes --project thenostrobserver
```

`project add` is a no-op if the project already exists (it errors "already
exists" — that is fine; deploy anyway). The project name **is** the
hostname: https://thenostrobserver.vercel.app. Do not rename it, and do not
let the CLI pick a name from the folder (`dist` would become `dist.vercel.app`).
The path argument is `dist`, never `.` and never `editions`.

The first deploy creates that project from this folder. There is no Git
link, so later prints do not go live until someone asks this skill again.

Give them https://thenostrobserver.vercel.app. Mention that pictures load here (unlike the
artifact viewer) because this is a real host.

---

## Hard rules

1. **Named editions only.** No name, no copy into `dist/`.
2. **Never deploy `editions/`.** It holds the corpus.
3. **`check` is the gate.** Junk in `dist/` means stop.
4. **Do not add this repo as a Vercel Git project.** A git-connected project
   would deploy the source tree. CLI deploy of `dist/` is the whole path.
5. Removing a paper from `dist/` and redeploying takes it off the live site.
   Vercel keeps old deployment URLs; say so if they ask about unpublishing.
