#!/usr/bin/env bash
#
# Starts the sample application exactly as the README quick start tells a newcomer to, from a
# clean clone, and times how long it takes to answer. One definition, called by two workflows:
# the `sample-smoke` job in ci.yml (every pull request) and the one in release.yml (every tag).
#
# It lived as a copy-pasted step body inside release.yml until release run 35899901341, where
# it was the only job that ever ran the shipped application and it failed on the first tag -
# seven green pull requests had never started the sample, because the check existed on the tag
# path only. A single script means the pull-request check and the release check cannot drift.
#
#   tools/run-sample-smoke.sh          (from the repository root; Docker and a JDK required)
#
# What it proves: a reader who follows the README gets an application that serves. It does NOT
# prove the issuance pipeline works - SampleEndToEndTest does that, with a signed test-mode
# event and a validator-clean document - and it deliberately supplies no Stripe key and no
# webhook secret, because the sample as shipped has no Stripe intake (README, "Run the sample").
#
set -euo pipefail

cd "$(dirname "$0")/.."
budget="${SMOKE_BUDGET_SECONDS:-180}"

start=$(date +%s)

# Refuse to measure something else. A leftover application already listening on 8080 answers
# the readiness poll below, and this check then reports a green smoke run for a process it
# never started - which is exactly what happened the first time this script was run on a
# developer machine.
if curl -s -o /dev/null --max-time 2 http://localhost:8080/series/smoke-probe; then
  echo "::error::something is already listening on http://localhost:8080; stop it first, or this check would measure it instead of the sample"
  exit 1
fi

# The sample refuses to start without the issuance-chain secret, and ships no default for it.
# Generated per run and thrown away: this proves a newcomer can get the sample answering, not
# that any particular key works.
EINVOICE_CHAIN_SECRET="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
export EINVOICE_CHAIN_SECRET

(cd stripe-einvoice-sample && docker compose up -d)

# Build the sample and the two libraries it needs. Tests already ran in the build job; this
# one measures how long a newcomer waits, not whether the tests pass.
./mvnw -B -q -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    -pl stripe-einvoice-sample -am package

log="$(mktemp)"
# Exactly one runnable jar, named: the glob used to be stripe-einvoice-sample-*.jar, which also
# matches the -sources and -javadoc jars a release build leaves in the same directory, and the
# smoke check then reported "the sample exited before it answered" for a javadoc jar with no main
# class. A wrong artifact must be a refusal with its own message, not a failed application.
runnable=""
count=0
while IFS= read -r candidate; do
  runnable="$candidate"
  count=$((count + 1))
done < <(
  find stripe-einvoice-sample/target -maxdepth 1 -name 'stripe-einvoice-sample-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*.jar.original' | sort
)
if [ "$count" -ne 1 ]; then
  echo "::error::expected exactly one runnable sample jar in stripe-einvoice-sample/target, found $count"
  exit 1
fi
java -jar "$runnable" > "$log" 2>&1 &
app=$!

# "Running" = the series-report endpoint answers. 200, 404 (the endpoint is open and the probe
# series does not exist) and 401 (Spring Security refusing an unauthenticated caller) are all
# correct answers: each means the application is up and serving.
ready=""
for _ in $(seq 1 120); do
  if ! kill -0 "$app" 2>/dev/null; then
    echo "::error::the sample exited before it answered"; tail -50 "$log"; exit 1
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/series/smoke-probe || true)
  if [ "$code" = "401" ] || [ "$code" = "200" ] || [ "$code" = "404" ]; then ready=1; break; fi
  sleep 1
done
end=$(date +%s)
elapsed=$(( end - start ))

if [ -z "$ready" ]; then
  echo "::error::the sample never answered"; tail -80 "$log"; kill "$app" || true; exit 1
fi

# The sample must come up with no Stripe intake and say so, rather than starting silently in a
# state a reader would take for a working webhook endpoint. The line comes from the starter's
# intake wiring check; its absence means the sample's shipped configuration has drifted away
# from the state the README documents.
if ! grep -q 'numbering API only, as configured' "$log"; then
  echo "::error::the sample started without the 'numbering API only, as configured' line: its shipped configuration no longer matches what the README describes"
  tail -80 "$log"; kill "$app" || true; exit 1
fi

echo "sample reached a first response in ${elapsed}s (clone -> running)"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### Sample: running in ${elapsed}s" >> "$GITHUB_STEP_SUMMARY"
fi

kill "$app" || true

# The target is under two minutes on a developer machine. A GitHub runner is slower and starts
# a PostgreSQL container from scratch, so the gate here is three minutes; the number printed
# above is the one that goes in the release notes.
if [ "$elapsed" -gt "$budget" ]; then
  echo "::error::sample took ${elapsed}s, over the ${budget}s budget"; exit 1
fi
