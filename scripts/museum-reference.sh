#!/usr/bin/env bash
# Prints what iOS's Museum makes of a catalog, from the iOS app's own MuseumCatalog.swift compiled
# with swift-corelibs-foundation: the orders of a few queries, each work's painting title, the
# shared names and two shuffle hashes. MuseumQueryTest compares the Android port with it, line by
# line, on app/src/test/resources/museum/catalog.json (40 works of the real catalog, three
# without a date, four with diacritics, and one of a kind no build knows).
#
#   scripts/museum-reference.sh <paint-by-number checkout> > app/src/test/resources/museum/ios.txt
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$1"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
python3 - "$IOS/App/Shared/Features/Museum/MuseumCatalog.swift" "$WORK/ref.swift" <<'PY'
import sys
src = open(sys.argv[1]).read()
body = src[src.index("nonisolated struct MuseumCatalog"):src.index("/// The catalog as the app holds it")]
body = body.replace("nonisolated ", "").replace("UserDefaults = .era", "UserDefaults = .standard")
main = "import Foundation\ntypealias CGFloat = Double\n" + body + r'''
let data = try! Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let catalog = try! MuseumCatalog.decode(data)
print("works", catalog.works.count)
func show(_ name: String, _ q: MuseumQuery) { print(name, q.works(of: catalog).map(\.id).joined(separator: ",")) }
show("colorful", MuseumQuery())
show("ukiyo", MuseumQuery(movement: "Ukiyo-e"))
show("oldest", MuseumQuery(order: .oldest))
show("newest", MuseumQuery(order: .newest))
show("shuffled", MuseumQuery(order: .shuffled, seed: 12345))
show("paintings", MuseumQuery(kinds: .paintings))
show("prints", MuseumQuery(kinds: .printsAndDrawings, order: .oldest))
show("search-gerome", MuseumQuery(search: "gerome"))
show("search-felicien", MuseumQuery(search: "FÉLIC ro"))
for w in catalog.works { print("title", w.id, w.paintingTitle) }
print("shared", catalog.sharedNames.sorted().joined(separator: "|"))
print("mix", MuseumQuery.mix("aic-100037", 1), MuseumQuery.mix("cma-150694", 0xffffffffffffffff))
'''
open(sys.argv[2], "w").write(main)
PY
swiftc -O "$WORK/ref.swift" -o "$WORK/ref"
"$WORK/ref" "$ROOT/app/src/test/resources/museum/catalog.json"
