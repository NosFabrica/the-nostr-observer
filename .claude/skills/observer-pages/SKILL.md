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

## Step 1 — Ask for the project name, then show the shelf

> Which Vercel project should this shelf live in? (The name **is** the
> hostname — `myobserver` becomes `https://myobserver.vercel.app`.)

Ask once, the first time. It is created in their own Vercel account under a
name only they choose, so there is nothing to derive and nothing sensible to
guess. If they have deployed before, they can say the name they used.

That name is the shelf's public origin, and every `site.mjs` command below
needs it, because Open Graph tags are absolute URLs and a wrong one hands
every reader who shares a paper a link to somebody else's site. Each Bash call
is a fresh shell, so pass it **inline on every command** rather than exporting
it:

```bash
OBSERVER_ORIGIN=https://<project>.vercel.app node <skill>/scripts/site.mjs list
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
OBSERVER_ORIGIN=https://<project>.vercel.app node <skill>/scripts/site.mjs add <edition...>
```

Selectors: `observer-2026-08-27-1EAF35.html`, `1EAF35`, `2026-08-27`, or
`today`. To take a paper down:

```bash
OBSERVER_ORIGIN=https://<project>.vercel.app node <skill>/scripts/site.mjs remove <edition...>
```

Then:

```bash
node <skill>/scripts/site.mjs check
```

`check` reads no URLs, so it needs no origin.

**Exit 0 or do not deploy.** A non-zero check means `dist/` contains something
that is not an edition — usually `corpus.json`. `favicon.svg` is site
furniture and is allowed. Fix junk before Vercel sees the folder. Do not
pass `--force`. Do not point Vercel at `editions/`.

`add` and `index` stamp each edition with Open Graph and Twitter Card meta
tags (canonical URL, lead headline, dek, first photograph) so link previews
work on the public shelf. Those URLs come from `OBSERVER_ORIGIN`; run without
it and the tags name a default hostname that is almost certainly not theirs.

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
npx vercel project add <project>
npx vercel deploy dist --prod --yes --project <project>
```

Use the name from Step 1, and the same one every time — the project name **is**
the hostname, so a second name is a second website with a different address on
every link already shared. `project add` is a no-op if the project already
exists (it errors "already exists" — that is fine; deploy anyway). Always pass
`--project`: left to itself the CLI names the project after the folder, and
`dist` would become `dist.vercel.app`. The path argument is `dist`, never `.`
and never `editions`.

The first deploy creates that project from this folder. There is no Git
link, so later prints do not go live until someone asks this skill again.

Give them `https://<project>.vercel.app`. If the URL Vercel prints back is a
different hostname, say so and re-run Step 2 with `OBSERVER_ORIGIN` set to the
real one, or every `og:url` on the shelf points somewhere else. Mention that
pictures load here (unlike the artifact viewer) because this is a real host.

### When the CLI fails from the agent shell

`npx vercel whoami` / `deploy` is the only deploy path. If either fails
unexpectedly — `fetch failed`, DNS errors, sandbox/proxy errors, a "token
not valid" that contradicts a whoami the reader just ran in their own
terminal, an approval block, anything else — **stop and ask them for help**.

Say what already succeeded (usually `add` + `check`), paste the exact
command they should run in their terminal, and end the turn:

```bash
npx vercel deploy dist --prod --yes --project <project>
```

Do **not** work around a broken agent shell. That means no Vercel MCP
deploy, no uploading `dist/` through another API, no token refresh scripts,
no alternate hosts, and no second deploy attempt through a different tool.
One failed CLI deploy is enough — hand it to the reader.

---

## Hard rules

1. **Named editions only.** No name, no copy into `dist/`.
2. **Never deploy `editions/`.** It holds the corpus.
3. **`check` is the gate.** Junk in `dist/` means stop.
4. **Do not add this repo as a Vercel Git project.** A git-connected project
   would deploy the source tree. CLI deploy of `dist/` is the whole path.
5. Removing a paper from `dist/` and redeploying takes it off the live site.
   Vercel keeps old deployment URLs; say so if they ask about unpublishing.
6. **CLI only; ask on failure.** Never use MCP or other back-channels to
   deploy. If `vercel` does not work from the agent shell, ask the reader.
