#!/usr/bin/env bash
# How the engine in generator/ differs from Amethyst's, file by file.
#
#   tools/diff-amethyst.sh [amethyst checkout] [git ref]   (default: ../amethyst, its working tree)
#
# generator/src/main/kotlin/com/nosfabrica/observer/frontpage/ is a port of Amethyst's
# commons/src/{commonMain,jvmAndroid}/kotlin/com/vitorpamplona/amethyst/commons/frontpage/ (and
# its tests, of {commonTest,jvmTest}). The two are meant to diff cleanly: this rewrites
# Amethyst's copy the way the port did (package, imports, no Compose @Immutable, expect/actual
# folded into plain JVM code) and prints a unified diff of what is left.
#
# Deliberately NOT ported, because they are the app, not the engine:
#   FrontPageViewModel, FrontPageMorning (the per-device claim), FrontPageSchedule (local
#   prefs), FrontPagePrintJob, FrontPageModelMemory, ObserverPrefs, ModelChoices (the picker),
#   ObserverSiteIO's AccountObserverSiteIO, FrontPageEditionStore, and the FrontPagePrinter
#   class (only its account-free declarations are here). The runner (runner/) is this repo's
#   printer and its ObserverSiteIO.
# Deliberately different:
#   ObserverPublisher takes `forReader`, for a runner's site (to be upstreamed).
#   Platform.kt stands in for the bits of Amethyst's commons the engine imports.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
amethyst="${1:-$root/../amethyst}"
ref="${2:-}"
fp="kotlin/com/vitorpamplona/amethyst/commons/frontpage"
ours="$root/generator/src"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

normalise() {
  sed -e 's/com\.vitorpamplona\.amethyst\.commons\.frontpage/com.nosfabrica.observer.frontpage/g' \
      -e 's/import com\.vitorpamplona\.amethyst\.commons\.[a-z.]*\.\([A-Za-z]*\)$/import com.nosfabrica.observer.frontpage.\1/' \
      -e '/^import androidx\.compose\.runtime\.Immutable$/d' \
      -e '/^ *@Immutable$/d' \
      -e 's/\bactual //g' "$1"
}

status=0
for set in commonMain:main jvmAndroid:main commonTest:test jvmTest:test; do
  from="${set%%:*}"; to="${set##*:}"
  for f in "$ours/$to/kotlin/com/nosfabrica/observer/frontpage/"*.kt; do
    name="$(basename "$f")"
    rel="commons/src/$from/$fp/$name"
    if [ -n "$ref" ]; then
      git -C "$amethyst" show "$ref:$rel" > "$tmp/$name.orig" 2>/dev/null || continue
    else
      [ -f "$amethyst/$rel" ] || continue
      cp "$amethyst/$rel" "$tmp/$name.orig"
    fi
    normalise "$tmp/$name.orig" > "$tmp/$name"
    if ! diff -u --label "amethyst/$from/$name" --label "generator/$to/$name" "$tmp/$name" "$f"; then status=1; fi
  done
done
exit $status
