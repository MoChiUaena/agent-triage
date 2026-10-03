# History growth validation

`HistoryGrowthTest` writes synthetic snapshots through `RunRepository` and queries the real `HistoryRepository`. It checks pagination, filtered results, statistics, retention, and persistence after closing the connection pool and creating fresh repositories. It does not start the application or call a model.

The normal test suite uses a new H2 file in JUnit's temporary directory at 120, 240, and 360 rows. Run it alone with JDK 21:

```sh
./mvnw -B -ntp -Dtest=HistoryGrowthTest test
```

An explicit larger run stays bounded at 10,000 rows:

```sh
./mvnw -B -ntp -Dtest=HistoryGrowthTest -Dhistory.growth.checkpoints=1000,5000,10000 test
```

On Windows, replace `./mvnw` with `.\mvnw.cmd` and quote each `-D...` argument. Checkpoints must contain 3–5 increasing counts within 24–10,000.

The **History growth** workflow runs both backends on relevant pull requests. Its manual choices include 5,000 and 10,000 rows. PostgreSQL uses a new PostgreSQL 16 service with the dedicated `history_growth_ci` database. For a separately created local test database, set `HISTORY_GROWTH_DB_URL`, `HISTORY_GROWTH_DB_USER`, and `HISTORY_GROWTH_DB_PASSWORD`, then add `-Dhistory.growth.postgres=true`. The fixture accepts only `jdbc:postgresql://localhost:PORT/history_growth_ci` or the `127.0.0.1` equivalent, without URL parameters. It creates a random private schema and drops only that schema. Shared application database variables are never read.

Each twelve-row cycle includes QUEUED and RUNNING records older than the cutoff, all four terminal statuses on both sides of it, and a terminal record exactly at the cutoff. Two services, endpoint and whole-service records, complete/partial/missing model usage, and timestamp ties exercise the stored projections. Snapshots have 4/8/16 events and 2/4/8 evidence items, with deterministic 160/512/2,048-character synthetic summaries and numeric request metrics. These are bounded storage fixtures, not recordings of business requests or a prediction of real payload sizes.

Successful runs write numeric CSV rows to `target/history-growth/h2.csv` and, when enabled, `postgres.csv`. Headers describe the columns; cells contain numbers only. `backend` is 1 for H2 and 2 for PostgreSQL. `phase` is 0 while growing, 1 after retention, and 2 after reopening. The workflow uploads only these CSV files.

`payload_bytes` counts UTF-8 bytes of the JSON actually stored. `physical_bytes` measures H2's complete `.mv.db` file or PostgreSQL's `triage_runs` table, indexes, and TOAST storage; those scopes differ. `projected_rows` checks persisted history projections. `load_ns` includes repository serialization and inserts in transactions of at most 100 rows. Cleanup reports the deleted row count and elapsed time.

Page, cursor page, filtered page, statistics, and filtered statistics timings use one warm-up followed by five samples, reporting minimum/median/maximum nanoseconds. These describe the machine and cache state of that run. No absolute speed threshold is enforced. Full cursor walks check for gaps and duplicates before cleanup, after cleanup, and after reopening.

Retention must reduce logical rows and payload bytes while preserving old active records, recent terminal records, and the cutoff boundary. Physical files need not shrink: database engines can retain allocated space for reuse. The fixture reports that size without treating retained allocation as a leak. This validates repository persistence, not recovery from a killed process, the browser/API workflow, or production capacity.
