Agent Triage — local LIVE demo

Requirements: JDK 21 or later. macOS/Linux also need Bash and curl.
Extract the entire archive. No Maven, database server or API Key is needed for the first run.

Windows PowerShell:
  powershell -ExecutionPolicy Bypass -File .\start-demo.ps1

macOS / Linux:
  bash start-demo.sh

Open http://127.0.0.1:18080. Generate normal or inventory-timeout requests, then start an investigation.
Order and inventory services run on 18082 and 18084. The database sample runs on 18086.
The page service selector includes independent Starter product, price and ticket services.
Create product requests at http://127.0.0.1:18088/api/products/demo and price requests at
http://127.0.0.1:18089/api/prices/demo. These two applications have no fault controls.
The product service shares the inventory fixture, so delayed inventory responses affect it too.
The ticket service runs on 18090; /api/tickets/summary is healthy and /api/tickets/T-1 returns 504.
Its separate assignment dependency delays actual HTTP responses on 18092. Register the bundled
projects/ticket-service directory in the source page, then use the integration check and code references.
Follow SOURCE_DEMO.md to compare endpoints, inspect error positions and test build source differences.
OBSERVATIONS.md explains inbound HTTP V4, optional request failure counts, response classes, observation tokens and async context wrappers.
These new switches stay disabled in the bundled demo; they can be enabled explicitly when integrating another application.
Ctrl+C stops all eight processes started by the launcher.

If the ports are occupied, use an alternative base port:
  Windows: powershell -ExecutionPolicy Bypass -File .\start-demo.ps1 -BasePort 18180
  macOS/Linux: bash start-demo.sh 18180
Ports are base (Agent), base+2 (order), base+4 (inventory), base+6 (database),
base+8 (catalog HTTP), base+9 (catalog JDBC), base+10 (ticket) and base+12 (assignment).
The launcher never stops an existing instance to free ports.
On Windows, add -CheckOnly to verify startup and immediately stop this launch.

The first run uses fixed rules with actual local HTTP request observations. Model configuration is optional,
available in the settings page, and persists under data/. An enabled real model can incur provider fees.
The page supports cancellation, known token usage and specific connection/protocol failure guidance.
Open workspace management from the sidebar to page and filter the full history, inspect execution statistics,
or check registered observation services. Deletion requires confirmation and is restricted to terminal records.
Database V3/V6 index existing history without rewriting saved execution JSON. No automatic cleanup is enabled.
Cancelling a local run cannot guarantee that a provider stops executing or billing an accepted request.
config/services.yml registers five local services; the plain Starter JAR, optional Agent JAR and POM are under sdk/.
The Agent JAR is not needed for this demo. For another application, pass its path with -javaagent
and use the same-build Starter dependency; see docs/STARTER.md in the source repository.
The Starter is not published on Maven Central. Dependency setup is documented in the source repository.
To upgrade from v0.6.0, stop the old launch and back up the database together with data/model-config.key.
Finish in-flight work before stopping v0.6.0: its delayed H2 writes can lose the latest setting on an abrupt stop.
The new default local H2 store uses synchronous writes; custom datasource URLs retain their configured behavior.
Move the backed-up data directory to the new complete extraction, then start the new launcher.
Review custom service ports and source-project directories; keep the original package and backup until the upgrade is checked.
This bundle is for local single-instance use and has no multi-user login or production authentication.

Keep data/triage.mv.db and data/model-config.key together when backing up saved model settings.
Never share data/, logs/, .env or model keys. The release archive contains none of these local files.
If a service stops unexpectedly, inspect logs/. All services bind to localhost.

Source, documentation and evaluations:
https://github.com/MoChiUaena/agent-triage
