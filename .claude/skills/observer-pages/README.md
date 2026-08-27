# Observer pages

Put selected Nostr Observer editions on the public web, on Vercel, without a
second GitHub repository. An index lists only the papers that have been
chosen. Everything else stays in `editions/` on this machine.

The print skill (`nostr-observer`) writes every run into `editions/`. This
skill copies named HTML files into `dist/` and deploys that folder.

## Why two folders

A print run also writes `corpus.json` — the day's events, megabytes of other
people's posts. Deploying the print folder would publish that file. `dist/`
is allowed to contain edition HTML, `index.html`, and `vercel.json`. Nothing
else.

## Use it

From the directory that holds `editions/` (this repository, if that is where
the papers landed):

```
> deploy today's paper
> put the August 22 edition online
> take the 183A1C paper down
```

The first deploy on a machine needs `npx vercel login` once. Hobby is free.
The project is named `thenostrobserver`, so the site is
https://thenostrobserver.vercel.app. There is no GitHub Pages repo and no Git
integration: later papers go live only when you ask.

Preview the shelf locally with `npx serve dist`. All printed papers, including
unpublished ones: `npx serve editions`.

The tab icon is `favicon.svg`: a cream tile, a masthead rule, and an Observer
O. `site.mjs` copies it into `dist/` and stamps a link into each published
page.
