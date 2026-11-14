#!/usr/bin/env bash
# Linux/macOS JMeter runner. Windows users should use run.ps1.
# Required: TOKEN, PROJECT_ID, TASK_ID, SESSION_ID and JMETER_HOME.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:8090}"
MGMT_URL="${MGMT_URL:-http://127.0.0.1:8091}"
RESULTS_DIR="${RESULTS_DIR:-$REPO_ROOT/target/perf/results/$(date +%Y%m%d-%H%M%S)}"
JMETER_HOME="${JMETER_HOME:-$REPO_ROOT/target/perf-tools/apache-jmeter-5.6.3}"
JMETER="$JMETER_HOME/bin/jmeter"

for value in TOKEN PROJECT_ID TASK_ID SESSION_ID; do
  if [ -z "${!value:-}" ]; then
    echo "missing required environment variable $value" >&2
    exit 2
  fi
done
if [ ! -x "$JMETER" ]; then
  echo "JMeter not found at $JMETER; set JMETER_HOME" >&2
  exit 2
fi
mkdir -p "$RESULTS_DIR"

scheme() { echo "$1" | sed -E 's#^([a-z]+)://.*#\1#'; }
host() { echo "$1" | sed -E 's#^[a-z]+://([^/:]+).*#\1#'; }
port() { echo "$1" | sed -E 's#^[a-z]+://[^/:]+:([0-9]+).*#\1#'; }

SERVER="$(host "$BASE_URL")"
PORT="$(port "$BASE_URL")"
PROTOCOL="$(scheme "$BASE_URL")"
MGMT_SERVER="$(host "$MGMT_URL")"
MGMT_PORT="$(port "$MGMT_URL")"
MGMT_PROTOCOL="$(scheme "$MGMT_URL")"
[ "$PORT" = "$BASE_URL" ] && PORT=80
[ "$MGMT_PORT" = "$MGMT_URL" ] && MGMT_PORT=80

COMMON=(
  -Jjmeter.save.saveservice.output_format=csv
  -Jjmeter.save.saveservice.default_delimiter=,
  -Jjmeter.save.saveservice.print_field_names=true
  -Jjmeter.save.saveservice.response_data=false
  -Jjmeter.save.saveservice.samplerData=false
  -Jjmeter.save.saveservice.requestHeaders=false
  -Jjmeter.save.saveservice.responseHeaders=false
  -Jjmeter.save.saveservice.response_message=false
  -Jjmeter.save.saveservice.failure_message=false
  -Jjmeter.save.saveservice.assertion_results_failure_message=false
  -Jsample_variables=overheadMs,providerLatencyMs
  "-Jserver=$SERVER" "-Jport=$PORT" "-Jprotocol=$PROTOCOL"
  "-JmgmtServer=$MGMT_SERVER" "-JmgmtPort=$MGMT_PORT" "-JmgmtProtocol=$MGMT_PROTOCOL"
  "-Jtoken=$TOKEN" "-JprojectId=$PROJECT_ID"
  "-JtaskId=$TASK_ID" "-JsessionId=$SESSION_ID"
  "-JaiServiceToken=${AI_SERVICE_TOKEN:-NOT_SET}"
)

run_scenario() {
  name="$1"
  jmx="$2"
  shift 2
  jtl="$RESULTS_DIR/$name.jtl"
  rm -f "$jtl" "$RESULTS_DIR/$name.jmeter.log"
  echo "Running $name ..."
  "$JMETER" -n -t "$SCRIPT_DIR/$jmx" -l "$jtl" \
    -j "$RESULTS_DIR/$name.jmeter.log" "${COMMON[@]}" "$@"
  echo "$name exit=$?"
}

run_scenario smoke smoke.jmx
run_scenario read-baseline-b1 read-baseline.jmx -Jthreads="${THREADS:-10}" -Jramp="${RAMP:-10}" -Jduration="${DURATION:-60}"
run_scenario read-baseline-b2 read-baseline.jmx -Jthreads="${THREADS:-10}" -Jramp="${RAMP:-10}" -Jduration="${DURATION:-60}"
run_scenario mixed-baseline mixed-baseline.jmx -Jthreads="${MIXED_THREADS:-8}" -Jramp="${MIXED_THREADS:-8}" -Jduration="${DURATION:-60}"
run_scenario spike spike.jmx
if [ "${SKIP_SOAK:-0}" != "1" ]; then
  run_scenario soak soak.jmx -Jthreads="${SOAK_THREADS:-6}" -Jramp="${SOAK_RAMP:-10}" -Jduration="${SOAK_DURATION:-600}"
fi
if [ "${AI_SERVICE_TOKEN:-}" != "" ]; then
  run_scenario ai-stub ai-stub.jmx -Jthreads="${AI_THREADS:-5}" -Jramp=2 -Jduration="${AI_DURATION:-60}"
fi
run_scenario sse-smoke sse-smoke.jmx

node "$SCRIPT_DIR/tools/summarize.mjs" "$RESULTS_DIR"
