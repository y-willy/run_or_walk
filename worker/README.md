Run-or-walk Worker source and local tests. Requires Node.js 22 or later.

```sh
cd worker
npm ci
npm run check
npm test
npm run build
```

Tests run the real Workers runtime and SQLite Durable Object with fake upstream responses; they do not use a Seoul API key. `build` bundles locally without deployment. See [Cloudflare setup, policy, and deployment notes](../docs/CLOUDFLARE_WORKERS_NOTES.md) before deploying to the existing account. A new ledger holds upstream calls for 24 hours to protect the unknown preceding usage; `BUDGET_WARMUP` includes retry timing. This clock starts when the singleton object is first created, normally by the first `/subway` request, rather than at deployment. The existing `SEOUL_SUBWAY_KEY` secret remains in Cloudflare; do not copy its value into local files.

The first-window hold can be disabled with `BUDGET_BOOTSTRAP_HOLD_SECONDS=0` **only after verifying zero upstream calls for the entire preceding 24 hours and confirming no other service consumes that key**. Auditing a nonzero usage count does not satisfy this condition. Production configuration leaves the override absent; other values retain the full 24-hour hold. Its deadline is persisted, so later changing the variable does not shorten an existing hold.

In this cloud workspace the home configuration directory is read-only. Use `WRANGLER_LOG_PATH=/tmp/run-or-walk-wrangler-logs npm test`; for the dry build, create `/tmp/run-or-walk-config/.wrangler` and run `XDG_CONFIG_HOME=/tmp/run-or-walk-config WRANGLER_LOG_PATH=/tmp/run-or-walk-wrangler-logs npm run build`. No account authentication is needed for either check. Wrangler can warn about the absent local secret while the tests use their explicit fake binding.

`src/stations.json` contains the 563 unique names from the existing Android asset's 696 official station rows. Refresh it when that asset changes. The public `destination` alias keeps the existing `trainLineNm` value; `bstatnNm` remains a separate original field. Cached responses retain their original `fetchedAt`, recheck the 60-second source-age limit, and keep a future-at-query timestamp unusable even after the clock catches up.

Each row's `station` alias comes only from its supplied `statnNm`; absent source fields stay absent. A row needs real train, service, destination, direction, and station identifiers before it can be `VALID`. Successful responses require consistent official error metadata. Upstream failures have a separate persisted 10-second backoff per station, return their HTTP error and remaining `Retry-After`, and consume no additional upstream budget while that backoff is active. These errors never masquerade as fresh arrival data.
