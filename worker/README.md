Run-or-walk Worker source and local tests. Requires Node.js 22 or later.

```sh
cd worker
npm ci
npm run check
npm test
npm run build
```

Tests run the real Workers runtime and SQLite Durable Object with fake upstream responses; they do not use a Seoul API key. `build` bundles locally without deployment. See [Cloudflare setup, policy, and deployment notes](../docs/CLOUDFLARE_WORKERS_NOTES.md) before deploying to the existing account. New and upgraded subway ledgers can fetch immediately; the previous automatic 24-hour bootstrap hold has been removed. The existing `SEOUL_SUBWAY_KEY` secret remains in Cloudflare; do not copy its value into local files.

Upgrading clears only the obsolete `ledger_metadata.upstream_not_before` deadline. It does **not** delete the Durable Object, change its namespace, erase attempts, or reset the rolling 900-attempt budget. Old `BUDGET_BOOTSTRAP_HOLD_SECONDS` variables are no longer used and no new flag is needed. A genuine exhausted budget still returns `UPSTREAM_BUDGET_EXHAUSTED` with `Retry-After`; short-lived IP/source backoff still applies. Calls made outside this Worker are not represented by its ledger, so check the provider's actual usage if the key has other consumers.

In this cloud workspace the home configuration directory is read-only. Use `WRANGLER_LOG_PATH=/tmp/run-or-walk-wrangler-logs npm test`; for the dry build, create `/tmp/run-or-walk-config/.wrangler` and run `XDG_CONFIG_HOME=/tmp/run-or-walk-config WRANGLER_LOG_PATH=/tmp/run-or-walk-wrangler-logs npm run build`. No account authentication is needed for either check. Wrangler can warn about the absent local secret while the tests use their explicit fake binding.

`src/stations.json` contains the 563 unique names from the existing Android asset's 696 official station rows. Refresh it when that asset changes. The public `destination` alias keeps the existing `trainLineNm` value; `bstatnNm` remains a separate original field. Cached responses retain their original `fetchedAt`, recheck the 60-second source-age limit, and keep a future-at-query timestamp unusable even after the clock catches up.

Each row's `station` alias comes only from its supplied `statnNm`; absent source fields stay absent. A row needs real train, service, destination, direction, and station identifiers before it can be `VALID`. Successful responses require consistent official error metadata. Upstream failures have a separate persisted 10-second backoff per station, return their HTTP error and remaining `Retry-After`, and consume no additional upstream budget while that backoff is active. These errors never masquerade as fresh arrival data.

## Seoul city buses and Seoul metropolitan buses

The bus adapter uses a **separate encrypted Secret**, `SEOUL_BUS_KEY`. Register the public-data portal's bus key in the existing Worker, either in Cloudflare **Workers & Pages → run-or-walk → Settings → Variables and Secrets → Add → Secret**, or through the CLI prompt:

```sh
npx --no-install wrangler secret put SEOUL_BUS_KEY
```

Do not put the value in the command, `wrangler.jsonc`, Android `local.properties`, or Git. Keep `SEOUL_SUBWAY_KEY` unchanged. The bus key is optional for existing subway endpoints: an absent bus key produces `SEOUL_BUS_NOT_CONFIGURED` only on bus requests. The same portal key needs utilization approval for both **Seoul bus arrival information** and **Seoul station information**; the latter resolves the actual stop ID, ARS number and route stop order.

`npm run deploy` also creates the `SEOUL_BUS_COORDINATOR` SQLite Durable Object, using the appended `v2` migration while preserving `v1`. Pasting only the JavaScript into Dashboard Edit code does not provision these bindings. Existing migration history must be retained.

The new HTTPS endpoints are:

- `GET /bus/stops?query=서울역` or `GET /bus/stops?x=126.97&y=37.55` (nearby radius 500m); a five-digit query searches the exact ARS number.
- `GET /bus/routes?stopId=100000001&arsId=01001` returns official route IDs, stop orders, direction labels and route types. The IDs here are illustrative; use the preceding real lookup result.
- `GET /bus/arrivals?stopId=100000001&routeId=100100001&stopOrder=7` verifies all three IDs against the actual arrival row. A Kakao place ID never substitutes for the official stop ID.

The fixed official upstream uses `resultType=json`, supported by the 2025 official guides. The `mkTm` KST provision timestamp (including fractional seconds) anchors `exps1/2`; `fetchedAt` is never substituted for an absent source timestamp. The two source slots retain actual `vehId1/2`, capacity and last-bus flags. Full, capacity-unknown, zero-ETA, stale/future, unsupported-provider, unconfirmed vehicle-state and detoured buses cannot authorize a boarding recommendation. Repeated vehicle IDs do not prove a next bus. Route types 2–6 cover the currently supported Seoul local/city/metropolitan scope; Incheon, Gyeonggi and airport routes are displayed as unsupported rather than silently mapped to Seoul service.

Bus requests share the existing IP rate limiter. The bus singleton provides 30-second cache/coalescing and separate rolling 24-hour limits of 900 upstream attempts for **station metadata** and **arrival service**. Failures consume one attempt and receive a persisted 10-second backoff. These ledgers do not reset or consume the subway ledger. There is no new initial 24-hour hold for the new bus ledger; previous calls made outside this Worker are not covered by its counters. Service allocations and actual approved limits must be checked in the portal before production use.

See [Korean bus integration and key-registration guide](../docs/SEOUL_BUS_INTEGRATION.md). Real credentials and actual bus-service success have not been verified in this workspace; tests use explicit fake keys and provider responses.
