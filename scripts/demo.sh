#!/usr/bin/env bash
# The transcript in the README, as a script. Run it to reproduce every line of it.
#
# Exists because a README transcript that was typed by hand is a README transcript that is wrong by
# the next release. This one produces it.
set -euo pipefail
# Job control off, so the shell does not print its own "Killed: 9" line over the transcript
# when the kill -9 below does exactly what it is supposed to do.
set +m

cd "$(dirname "$0")/.."
jar="cairn-server/target/cairn-server-0.1.0.jar"
[ -f "$jar" ] || { echo "build it first: mvn package -DskipTests" >&2; exit 1; }

data="$(mktemp -d)"
port="${PORT:-9099}"
export CAIRN_URL="http://127.0.0.1:$port"
ctl() { java -jar "$jar" "$@"; }
say() { printf '\n\033[1m$ cairnctl %s\033[0m\n' "$*"; }

java -jar "$jar" --data-dir="$data/registry" --port="$port" \
     --effects-log="$data/effects.jsonl" > "$data/server.log" 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true; rm -rf "$data"' EXIT

for _ in $(seq 1 60); do
  curl -sf "$CAIRN_URL/healthz" >/dev/null 2>&1 && break
  sleep 0.25
done

printf 'weights for the fraud model v1\n'            > "$data/fraud-v1.bin"
printf 'weights for the fraud model v2, retrained\n' > "$data/fraud-v2.bin"
printf 'shared embedding weights\n'                  > "$data/embeddings.bin"

say status
ctl status

say "put embeddings.bin"
emb=$(ctl put "$data/embeddings.bin" | awk '{print $1}')
echo "$emb"

v1=$(ctl put "$data/fraud-v1.bin" | awk '{print $1}')
v2=$(ctl put "$data/fraud-v2.bin" | awk '{print $1}')

say "publish embeddings@1.0.0 \$EMB --label=owner=platform"
ctl publish embeddings@1.0.0 "$emb" --label=owner=platform

say "publish fraud@1.0.0 \$V1 --parent=embeddings@1.0.0 --label=auc=0.9131"
ctl publish fraud@1.0.0 "$v1" --parent=embeddings@1.0.0 --label=auc=0.9131 --label=owner=risk

say "stage fraud@1.0.0 production"
ctl stage fraud@1.0.0 production >/dev/null && echo "fraud@1.0.0 -> production"

say "publish fraud@2.0.0 \$V2 --parent=fraud@1.0.0 --parent=embeddings@1.0.0"
ctl publish fraud@2.0.0 "$v2" --parent=fraud@1.0.0 --parent=embeddings@1.0.0 \
    --label=auc=0.9402 >/dev/null && echo "fraud@2.0.0 -> staging"

say "stage fraud@2.0.0 production          # the incumbent is archived in the same command"
ctl stage fraud@2.0.0 production | head -3

say "ls fraud"
ctl ls fraud

say "lineage fraud@2.0.0"
ctl lineage fraud@2.0.0

say "publish fraud@2.0.0 \$V1             # same version, different bytes"
ctl publish fraud@2.0.0 "$v1" || echo "exit $?"

say "rm fraud@2.0.0                        # it is in production"
ctl rm fraud@2.0.0 || echo "exit $?"

say "rm embeddings@1.0.0                   # two live versions descend from it"
ctl rm embeddings@1.0.0 || echo "exit $?"

say verify
ctl verify

printf '\n\033[1m$ kill -9 <the server>; start it again\033[0m\n'
kill -9 "$server"
sleep 1
java -jar "$jar" --data-dir="$data/registry" --port="$port" \
     --effects-log="$data/effects.jsonl" >> "$data/server.log" 2>&1 &
server=$!
for _ in $(seq 1 60); do
  curl -sf "$CAIRN_URL/healthz" >/dev/null 2>&1 && break
  sleep 0.25
done

say verify
ctl verify

say "state --at=6"
ctl state --at=6

printf '\n\033[1m$ tail -3 effects.jsonl\033[0m\n'
tail -3 "$data/effects.jsonl"

printf '\n\033[1m$ curl -s $CAIRN_URL/metrics | grep -E "outbox|dedup"\033[0m\n'
curl -s "$CAIRN_URL/metrics" | grep -E '^cairn_(outbox_depth|effects_deduplicated_total)'
