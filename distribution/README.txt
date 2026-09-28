Agent Triage — local LIVE demo

Requirements: JDK 21 or later. macOS/Linux also need Bash and curl.
Extract the entire archive. No Maven, database server or API Key is needed for the first run.

Windows PowerShell:
  powershell -ExecutionPolicy Bypass -File .\start-demo.ps1

macOS / Linux:
  bash start-demo.sh

Open http://127.0.0.1:18080. Generate normal or inventory-timeout requests, then start an investigation.
Order and inventory services run on 18082 and 18084. Ctrl+C stops the three child processes.

If the ports are occupied, use an alternative base port:
  Windows: powershell -ExecutionPolicy Bypass -File .\start-demo.ps1 -BasePort 18180
  macOS/Linux: bash start-demo.sh 18180
The other two ports are base+2 and base+4. The launcher never stops an existing instance to free ports.

The first run uses fixed rules with actual local HTTP request observations. Model configuration is optional,
available in the settings page, and persists under data/. An enabled real model can incur provider fees.
This bundle covers only the order/inventory example and has no multi-user login or production integration.

Keep data/triage.mv.db and data/model-config.key together when backing up saved model settings.
Never share data/, logs/, .env or model keys. The release archive contains none of these local files.
If a service stops unexpectedly, inspect logs/. All services bind to localhost.

Source, documentation and evaluations:
https://github.com/MoChiUaena/agent-triage
