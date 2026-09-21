# Observer pages

Put selected Nostr Observer editions on the public web, on Vercel, without a
second GitHub repository. An index lists only the papers that have been
chosen. Everything else stays in `editions/` on this machine.

The print skill (`nostr-observer`) writes every run into `editions/`. This
skill copies named HTML files into `dist/` and deploys that folder.

## Why two folders

A print run also writes `corpus.json` — the day's events, megabytes of other
people's posts. Deploying the print folder would publish that file. `dist/`
is allowed to contain edition HTML, `index.html`, `vercel.json` and
`favicon.svg`. Nothing else — `check` exits non-zero on anything it does not
recognise.

## Before the first deploy

- **Node 22 or newer**, same as the print skill. Nothing to `npm install`.
- **A Vercel account.** The free Hobby plan is enough; a personal shelf is well
  inside it. Sign up at [vercel.com](https://vercel.com) if you have none.
- **One login per machine**, in your own terminal, not through an agent:

  ```bash
  npx vercel login
  ```

  It opens a browser and stores the token itself, wherever the CLI keeps it
  on your platform. The skill never asks you for a token, never reads one out
  of the environment, and stops rather than working around a missing login.
- **A project name you pick and then keep.** The name *is* the hostname:
  `myobserver` becomes `https://myobserver.vercel.app`. Changing it later is a
  second website at a different address, and every link already shared points
  at the old one.

## Use it, through Claude

From the directory that holds `editions/` (this repository, if that is where
the papers landed):

```
> deploy today's paper
> put the August 22 edition online
> take the 183A1C paper down
```

It asks for the project name the first time, stages only the editions you
named, runs the `check` gate, and deploys. If the Vercel CLI fails from the
agent's shell — a proxy, a sandbox, an expired token — it stops and hands you
the command to run yourself instead of trying another route.

## Use it, by hand

Nothing here needs an agent. `site.mjs` is an ordinary CLI. Run it from this
`scripts/` directory, or give it an absolute path from wherever `editions/`
lives — it reads `./editions` and writes `./dist` relative to the directory
you are standing in, which `--editions` and `--dist` override.

```bash
export OBSERVER_ORIGIN=https://myobserver.vercel.app   # your project, as a URL

node site.mjs list                        # what is printed, what is public
node site.mjs add 2026-08-27              # stage one edition into dist/
node site.mjs check                       # MUST exit 0 before you deploy
npx serve dist                            # optional: look at it first

npx vercel project add myobserver
npx vercel deploy dist --prod --yes --project myobserver
```

`OBSERVER_ORIGIN` is what the link-preview tags are built from. Leave it unset
and they name a default hostname that is not yours — a freshly printed paper
carries `observer.invalid` until a shelf rewrites it, because the paper exists
before anyone has decided whether it goes on the web.

Full surface:

```
node site.mjs list   [--editions DIR] [--dist DIR] [--json]
node site.mjs add    <edition...> [--editions DIR] [--dist DIR]
node site.mjs remove <edition...> [--dist DIR]
node site.mjs index  [--dist DIR]
node site.mjs check  [--dist DIR]
```

An edition is a filename, a date (`YYYY-MM-DD`), a code, or `today`. Never
pass `editions/` as `--dist`: that folder holds the corpus.

**`check` is the gate, and it is not advisory.** It exits non-zero if `dist/`
holds anything that is not an edition or site furniture — usually
`corpus.json`, which is megabytes of other people's posts. Do not deploy past
a failing check, and do not point Vercel at `editions/`.

## Taking a paper down

```bash
node site.mjs remove 183A1C
npx vercel deploy dist --prod --yes --project myobserver
```

The paper leaves the index and the live site. **Vercel keeps every previous
deployment at its own immutable URL**, so anyone holding one of those older
links can still reach the paper there. Removing it from `dist/` is not a
retraction; if you need the old deployments gone, delete them in the Vercel
dashboard.

## What gets deployed

Only `dist/`, and only ever `dist/`: the editions you named, an `index.html`
listing them, `favicon.svg`, and a `vercel.json` that sets a strict
Content-Security-Policy — `default-src 'none'` with scripts, frames and forms
refused outright, inline styles allowed because the papers carry their own, and
images limited to this host, https and `data:` (the artifact copy inlines its
pictures). It also sends `nosniff` and `Referrer-Policy: no-referrer`. There
is no Git integration on purpose — a Git-connected project would deploy this
source tree, which is neither the editions nor a website. Later prints go live
only when you ask again.

Preview locally with `npx serve dist`. All printed papers, including
unpublished ones: `npx serve editions`.

The tab icon is `favicon.svg`: a cream tile, a masthead rule, and an Observer
O. `site.mjs` copies it into `dist/` and stamps a link into each published
page.
