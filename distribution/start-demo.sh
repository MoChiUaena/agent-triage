#!/usr/bin/env bash
set -euo pipefail
base_port=${1:-18080}
if [[ ! "$base_port" =~ ^[0-9]+$ ]] || (( base_port < 1024 || base_port > 65526 )); then
  echo 'Usage: bash start-demo.sh [base-port: 1024..65526]' >&2
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
for jar in agent-triage inventory-service order-service database-service catalog-service; do
  [[ -f "lib/$jar.jar" ]] || { echo "Missing lib/$jar.jar; extract the entire archive." >&2; exit 1; }
done
order_port=$((base_port + 2))
inventory_port=$((base_port + 4))
database_port=$((base_port + 6))
catalog_port=$((base_port + 8))
catalog_database_port=$((base_port + 9))
for port in "$base_port" "$order_port" "$inventory_port" "$database_port" "$catalog_port" "$catalog_database_port"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "Port $port is occupied. Stop the existing instance or choose another base port." >&2
    exit 1
  fi
done
[[ -f config/services.yml ]] || { echo 'Missing config/services.yml; extract the entire archive.' >&2; exit 1; }
mkdir -p logs
pids=()
cleanup() {
  for pid in "${pids[@]}"; do
    for running in $(jobs -pr); do
      if [[ "$running" == "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
    done
  done
  for attempt in {1..10}; do
    [[ -z "$(jobs -pr)" ]] && break
    sleep .5
  done
  for pid in "${pids[@]}"; do
    for running in $(jobs -pr); do
      if [[ "$running" == "$pid" ]]; then kill -KILL "$pid" 2>/dev/null || true; fi
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
java -jar lib/database-service.jar "--server.port=$database_port" > logs/database.out.log 2> logs/database.err.log &
pids+=($!)
java -jar lib/catalog-service.jar "--server.port=$catalog_port" "--triage.sdk.downstream-base-url=http://127.0.0.1:$inventory_port" > logs/catalog.out.log 2> logs/catalog.err.log &
pids+=($!)
java -jar lib/catalog-service.jar "--server.port=$catalog_database_port" --spring.profiles.active=database > logs/catalog-database.out.log 2> logs/catalog-database.err.log &
pids+=($!)
java -jar lib/agent-triage.jar "--server.port=$base_port" --triage.mode=DEMO --spring.config.additional-location=file:./config/services.yml "--demo.order-port=$order_port" "--demo.database-port=$database_port" "--demo.catalog-port=$catalog_port" "--demo.catalog-database-port=$catalog_database_port" > logs/agent.out.log 2> logs/agent.err.log &
pids+=($!)
ready=false
for attempt in {1..90}; do
  for pid in "${pids[@]}"; do
    kill -0 "$pid" 2>/dev/null || { echo 'A service stopped during startup. See logs/.' >&2; exit 1; }
  done
  if curl -fsS --max-time 2 "http://127.0.0.1:$inventory_port/lab/scenario" >/dev/null 2>&1 &&
     curl -fsS --max-time 2 "http://127.0.0.1:$order_port/lab/scenario" >/dev/null 2>&1 &&
     curl -fsS --max-time 2 "http://127.0.0.1:$base_port/api/config" | grep -q '"observationAvailable":true' &&
     curl -fsS --max-time 2 "http://127.0.0.1:$base_port/api/config?service=account-service" | grep -q '"observationAvailable":true' &&
     curl -fsS --max-time 2 "http://127.0.0.1:$base_port/api/config?service=catalog-service" | grep -q '"observationAvailable":true' &&
     curl -fsS --max-time 2 "http://127.0.0.1:$base_port/api/config?service=catalog-db-service" | grep -q '"observationAvailable":true'; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'Startup timed out. See logs/.' >&2; exit 1; }
echo "Open http://127.0.0.1:$base_port"
echo 'First launch uses LIVE observations and fixed rules. Model settings persist in data/; enabled models may incur fees.'
echo 'Four registered services are ready. Select order/database in the page, or call the catalog business endpoints.'
echo 'Press Ctrl+C to stop all six processes. Data and logs remain in this folder.'
while true; do
  for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || exit 1; done
  sleep 1
done
