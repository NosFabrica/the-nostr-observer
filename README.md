# The Observer

Your own daily newspaper, printed from your corner of Nostr.

Once a day the Observer reads what the people you trust posted in the last 24 hours and lays
it out as a front page: a lead story, photographs, a column for whatever the day actually
held. The stories are chosen by your own web of trust, the page is written by an AI you pay
for with your own key, and it is published as your own public nsite, on Blossom servers you
chose.

You print it from your own clients: [Amethyst](https://github.com/vitorpamplona/amethyst) on
Android and desktop, or [Brainstorm](https://brainstorm.world) on the web. Your schedule is one
setting shared by all of them. If you'd rather not keep a device on, your Brainstorm
Assistant can print it for you.

## What is in this repository

- **`generator/`**: the Observer's engine, a JVM library: the desks, the corpus and digest,
  the editorial brief and house stylesheet, the writer (any OpenAI-compatible endpoint your
  NIP-FA key names), the page checks, and the publisher for the Observer site. It follows
  Amethyst's copy, which is the reference. A small CLI does a local dry run.
- **`runner/`**: the Brainstorm Assistant's print worker. It runs on Brainstorm's servers,
  beside brainstorm_server, holds no private key, and prints for readers whose schedule names
  their Assistant. See [`runner/README.md`](runner/README.md).

The design is [`docs/PLAN.md`](docs/PLAN.md).

## Build

    ./gradlew build

---

Built by [NosFabrica](https://github.com/NosFabrica). MIT licensed.
