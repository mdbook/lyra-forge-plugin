#!/usr/bin/env bash
# Lyra overlay for a Forge Table release zip: unpack it over the table's top folder (Forge Table's
# `package.sh --overlay ZIP` does that) and the Neural AI profile has its seat, its runtime and its model.
#
# Usage: scripts/overlay.sh --model <lyra-model-vN.zip | unpacked model dir> [--out <zip | dir>]
#   --model  the Lyra model release asset (neural/ + LICENSE-MODEL.txt + MODEL-NOTICE.txt), as the zip or unpacked
#   --out    the overlay zip to write, or a directory for lyra-forge-plugin-<version>-overlay.zip (default target/)
# Run after `mvn package`. ONNX Runtime comes from the local Maven repo ($MAVEN_REPO, default ~/.m2/repository).
#
# The zip holds:
#   lib/       the plugin jar + the runtime jars Forge Table's lib/ lacks (ONNX Runtime). Never forge-* jars: the table
#              ships those. gson is in the table already (forge-gui depends on the same 2.13.2); jeromq is only for the
#              self-play CLI (the fat jar), the seat never loads it.
#   neural/    the model folder as the model zip has it, latest.json with "name" (default "Lyra") added
#   decks/     the .dck files latest.json "decks" names, from the model's archetype deck set
#   LICENSE-MODEL.txt, MODEL-NOTICE.txt, licenses/lyra-forge-plugin-LICENSE.txt
# It refuses a model whose sha256 or encoder_version doesn't match (a mismatched model would not load, and the seat
# would fall back to Forge's own AI without saying so in the lobby).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"
MODEL=
OUT="$REPO/target"
while [ $# -gt 0 ]; do
    case "$1" in
        --model) MODEL="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "$MODEL" ] || { echo "--model is required" >&2; exit 2; }
for t in zip unzip python3 sha256sum; do
    command -v "$t" >/dev/null || { echo "overlay.sh needs $t on PATH" >&2; exit 2; }
done
pomval() {  # the project's own <version>, or the <version> of dependency $1
    python3 -I - "$REPO/pom.xml" "${1:-}" <<'EOF'
import sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
if not sys.argv[2]:
    print(root.find("m:version", ns).text)
else:
    print(next(d.find("m:version", ns).text for d in root.findall("m:dependencies/m:dependency", ns)
               if d.find("m:artifactId", ns).text == sys.argv[2]))
EOF
}
VERSION="$(pomval)"
ORT_VERSION="$(pomval onnxruntime)"
PLUGIN_JAR="$REPO/target/lyra-forge-plugin-$VERSION.jar"
ORT_JAR="${MAVEN_REPO:-$HOME/.m2/repository}/com/microsoft/onnxruntime/onnxruntime/$ORT_VERSION/onnxruntime-$ORT_VERSION.jar"
for f in "$PLUGIN_JAR" "$ORT_JAR" "$REPO/LICENSE"; do
    [ -f "$f" ] || { echo "missing $f (run mvn package first; set MAVEN_REPO if your Maven repo is elsewhere)" >&2; exit 2; }
done
JAR_ENCODER="$(sed -n 's/.*static final int ENCODER_VERSION = \([0-9]*\);.*/\1/p' \
    "$REPO/src/main/java/mtgai/encode/ObservationEncoder.java")"
DECK_SET="$(sed -n 's/.*static final String DECK_SET = "\([^"]*\)";.*/\1/p' \
    "$REPO/src/main/java/mtgai/encode/ArchetypeClassifier.java")"
[ -n "$JAR_ENCODER" ] && [ -n "$DECK_SET" ] || { echo "can't read ENCODER_VERSION / DECK_SET from the sources" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/lyra-overlay.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
if [ -f "$MODEL" ]; then
    unzip -q "$MODEL" -d "$WORK/model"
    M="$WORK/model"
else
    M="$(cd "$MODEL" && pwd)"
fi
N="$M/neural"
for f in latest.json registry.tsv cardvocab-v1.txt "$DECK_SET/manifest.json"; do
    [ -f "$N/$f" ] || { echo "model has no neural/$f" >&2; exit 2; }
done
for f in LICENSE-MODEL.txt MODEL-NOTICE.txt; do
    [ -f "$M/$f" ] || { echo "model has no $f" >&2; exit 2; }
done

field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$N/latest.json" "$1"; }
MODEL_FILE="$(field file)"
MODEL_VERSION="$(field version)"
MODEL_SHA="$(field sha256)"
ENCODER_VERSION="$(field encoder_version)"
[ -f "$N/$MODEL_FILE" ] || { echo "model has no neural/$MODEL_FILE (latest.json names it)" >&2; exit 2; }
[ "$(sha256sum "$N/$MODEL_FILE" | cut -d' ' -f1)" = "$MODEL_SHA" ] || { echo "sha256 mismatch on $MODEL_FILE" >&2; exit 2; }
[ "$ENCODER_VERSION" = "$JAR_ENCODER" ] || {
    echo "model v$MODEL_VERSION is encoder $ENCODER_VERSION, this plugin is encoder $JAR_ENCODER" >&2; exit 2; }

S="$WORK/stage"
mkdir -p "$S/lib" "$S/decks" "$S/licenses"
cp "$PLUGIN_JAR" "$ORT_JAR" "$S/lib/"
cp -r "$N" "$S/neural"
# "name": what Forge Table's model label shows; a name the model zip already carries wins
python3 -I - "$S/neural/latest.json" "${LYRA_NAME:-Lyra}" <<'EOF'
import json, sys
p = sys.argv[1]
meta = json.load(open(p))
meta.setdefault("name", sys.argv[2])
open(p, "w").write(json.dumps(meta) + "\n")
EOF
python3 -I -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1])).get("decks", [])))' \
    "$S/neural/latest.json" | while read -r d; do
    [ -f "$N/$DECK_SET/$d.dck" ] || { echo "latest.json names deck $d, not in neural/$DECK_SET/" >&2; exit 2; }
    cp "$N/$DECK_SET/$d.dck" "$S/decks/"
done
cp "$M/LICENSE-MODEL.txt" "$M/MODEL-NOTICE.txt" "$S/"
cp "$REPO/LICENSE" "$S/licenses/lyra-forge-plugin-LICENSE.txt"
if compgen -G "$S/lib/forge-*" >/dev/null; then echo "a forge-* jar got into lib/" >&2; exit 2; fi

case "$OUT" in
    *.zip) mkdir -p "$(dirname "$OUT")"; ZIP="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")" ;;
    *) mkdir -p "$OUT"; ZIP="$(cd "$OUT" && pwd)/lyra-forge-plugin-$VERSION-overlay.zip" ;;
esac
rm -f "$ZIP"
(cd "$S" && zip -qrX "$ZIP" .)
(cd "$(dirname "$ZIP")" && sha256sum "$(basename "$ZIP")" | tee "$(basename "$ZIP").sha256")
echo "== $ZIP  $(stat -c %s "$ZIP") bytes  plugin $VERSION  model v$MODEL_VERSION (encoder $ENCODER_VERSION)"
