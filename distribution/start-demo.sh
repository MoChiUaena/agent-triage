#!/usr/bin/env bash
set -euo pipefail
base_port=${1:-18080}
if [[ ! "$base_port" =~ ^[0-9]+$ ]] || (( base_port < 1024 || base_port > 65531 )); then
  echo 'Usage: bash start-demo.sh [base-port: 1024..65531]' >&2
  exit 2
fi
command -v java >/dev/null || { echo 'JDK 21 or later is required.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required for local startup checks.' >&2; exit 1; }
java_info=$(java -version 2>&1)
if [[ ! "$java_info" =~ version[[:space:]]\"([0-9]+) ]] || (( BASH_REMATCH[1] < 21 )); then
  echo 'JDK 21 or later is required. Add its bin directory to PATH.' >&2
  exit 1
fi
bundle_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$bundle_root"
for jar in agent-triage inventory-service order-service; do
  [[ -f "lib/$jar.jar" ]] || { echo "Missing lib/$jar.jar; extract the entire archive." >&2; exit 1; }
done
order_port=$((base_port + 2))
inventory_port=$((base_port + 4))
mkdir -p logs
pids=()
cleanup() {
  for pid in "${pids[@]}"; do
    for running in $(jobs -pr); do
      if [[ "$running" == "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
    done
  done
  wait || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
java -jar lib/inventory-service.jar "--server.port=$inventory_port" > logs/inventory.out.log 2> logs/inventory.err.log &
pids+=($!)
java -jar lib/order-service.jar "--server.port=$order_port" "--sample.inventory.base-url=http://127.0.0.1:$inventory_port" > logs/order.out.log 2> logs/order.err.log &
pids+=($!)
java -jar lib/agent-triage.jar "--server.port=$base_port" --triage.mode=DEMO --triage.observation.source=LIVE "--triage.observation.base-url=http://127.0.0.1:$order_port" > logs/agent.out.log 2> logs/agent.err.log &
pids+=($!)
ready=false
for attempt in {1..60}; do
  for pid in "${pids[@]}"; do
    kill -0 "$pid" 2>/dev/null || { echo 'A service stopped during startup. See logs/.' >&2; exit 1; }
  done
  if curl -fsS --max-time 2 "http://127.0.0.1:$inventory_port/lab/scenario" >/dev/null 2>&1 &&
     curl -fsS --max-time 2 "http://127.0.0.1:$order_port/lab/scenario" >/dev/null 2>&1 &&
     curl -fsS --max-time 2 "http://127.0.0.1:$base_port/api/config" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'Startup timed out. See logs/.' >&2; exit 1; }
echo "Open http://127.0.0.1:$base_port"
echo 'First launch uses LIVE observations and fixed rules. Model settings persist in data/; enabled models may incur fees.'
echo 'Press Ctrl+C to stop all three services. Data and logs remain in this folder.'
while true; do
  for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || exit 1; done
  sleep 1
done
