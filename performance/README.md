# Booking flow checks with k6

Small local checks for inventory contention and booking-request idempotency.
These create real bookings and notification events in the selected environment.
Payment uses the backend's synchronous `CARD` simulation.
**Script available; final metrics pending execution.**

## Prerequisites and test data

- Install [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/).
- Run Gateway, Auth/Keycloak, Event and Booking services with their databases and
  required messaging configuration. Use an existing verified CUSTOMER account.
- In the shared management dashboard, use ADMIN or HOST to create a dedicated
  event with a future start time, status `PUBLISHED`, a positive ticket price
  and a small ticket tier (for example, total stock 10).
- Read `GET /event-service/api/v1/events/{eventId}` through the gateway to find
  the actual `eventId` and `ticketTypes[].ticketTypeId`. Ensure the tier belongs
  to that event. This public response exposes total quantity, **not current
  available quantity**; get the initial available stock from the database query
  below.
- Keep other traffic and inventory edits away from the test event. Use a fresh
  event/tier for each independent run. The idempotency run needs at least
  `QUANTITY` available tickets; do not run it against the sold-out inventory
  fixture. No script resets stock or cancels bookings automatically.

## Authentication and API contract

The verified service endpoint is:

```text
POST /booking-service/api/v1/bookings
Authorization: Bearer <actual access token>
Idempotency-Key: <logical request key>
X-Correlation-ID: <request correlation identifier>
```

`BASE_URL` is the gateway origin with optional `/api`, not the booking-service
path. Gateway rewrites the `/api` prefix. Request DTO:

```json
{
  "eventId": 1,
  "ticketSelections": [{ "ticketTypeId": 1, "quantity": 1 }],
  "paymentMethod": "CARD"
}
```

The IDs above illustrate the structure only; scripts require your actual IDs.
The JWT subject identifies the user. Completed replays currently return
`201` with the original booking. In-progress keys return `409`; changed
fingerprints return `409`.

From a PowerShell session, obtain a token using your real verified account:

```powershell
# Run from backend/booking-service-api/performance.
$env:BASE_URL = 'http://localhost:9090/api' # Change to your actual gateway.
$account = Get-Credential -Message 'Existing verified CUSTOMER account; username is email'
$loginBody = @{ email = $account.UserName; password = $account.GetNetworkCredential().Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$env:BASE_URL/user-service/api/v1/users/visitors/login" -ContentType 'application/json' -Body $loginBody
$env:JWT_TOKEN = $login.data.access_token
Remove-Variable loginBody, login, account
```

Alternatively reuse a fresh access token from the normal login flow.
Do not prepend `Bearer` in `JWT_TOKEN`. Do not save tokens/passwords in scripts,
screenshots or reports. The scripts do not log tokens or full personal responses.

## Environment variables

| Variable | Meaning |
|---|---|
| `BASE_URL` | Required gateway origin, optionally ending in `/api`; no default. |
| `JWT_TOKEN` | Required current access token for one CUSTOMER; no default. |
| `EVENT_ID`, `TICKET_TYPE_ID` | Required actual positive integer IDs. |
| `QUANTITY` | Tickets per logical booking; default `1`. |
| `INITIAL_STOCK` | Required for inventory: measured available quantity immediately before the run. |
| `ATTEMPTS` | Inventory logical requests; default `20`. Must exceed `floor(INITIAL_STOCK / QUANTITY)`. |
| `VUS` | Inventory concurrent virtual users; default `10`, at least `2`. |
| `REPLAYS` | Parallel completed-request replays; default `5`. |
| `RUN_ID` | Optional unique label, 1–50 letters/digits/hyphens. Generated once in setup when omitted. |
| `SUMMARY_FILE` | Optional summary JSON path; default includes scenario and run ID. |

## Run inventory concurrency

After authenticating and measuring stock:

```powershell
$env:EVENT_ID = Read-Host 'Actual inventory-test event ID'
$env:TICKET_TYPE_ID = Read-Host 'Actual ticket type ID for that event'
$env:INITIAL_STOCK = Read-Host 'Measured available stock before this run'
$env:QUANTITY = '1'
$env:ATTEMPTS = '20'
$env:VUS = '10'
$env:RUN_ID = 'inventory-' + (Get-Date -Format 'yyyyMMddHHmmss')
k6 run --out "json=$env:RUN_ID-metrics.json" ./booking-concurrency.js 2>&1 | Tee-Object "$env:RUN_ID-console.txt"
$LASTEXITCODE
```

Each logical attempt uses a unique key:
`k6-<RUN_ID>-inventory-<iteration>`. Expected outcomes are a confirmed `201`
or stock-exhausted `409`. Other 409 causes, 400/401/403/404, 5xx, timeouts and
malformed success responses fail checks. Expected stock conflicts are excluded
from k6's HTTP error rate; they still have their own counter.

Thresholds require all requested attempts, at least one confirmation and stock
rejection, zero unexpected responses, and confirmations no greater than
`floor(INITIAL_STOCK / QUANTITY)`. They do not require every request to succeed.
No latency/throughput target is asserted.

## Run idempotency

Choose a fresh published event/tier with sufficient stock; keep the same token:

```powershell
$env:EVENT_ID = Read-Host 'Actual idempotency-test event ID'
$env:TICKET_TYPE_ID = Read-Host 'Actual ticket type ID for that event'
$env:QUANTITY = '1'
$env:REPLAYS = '5'
$env:RUN_ID = 'idempotency-' + (Get-Date -Format 'yyyyMMddHHmmss')
k6 run --out "json=$env:RUN_ID-metrics.json" ./booking-idempotency.js 2>&1 | Tee-Object "$env:RUN_ID-console.txt"
$LASTEXITCODE
```

The script creates one successful booking, sends identical replays concurrently
with the same JWT/body/key, and checks identical booking ID/reference/amount.
It then changes quantity under the same key and requires the specific
request-fingerprint conflict. The key is `k6-<RUN_ID>-idempotency`.
This tests replay of a completed request, not a race to acquire a new key.
Unique-constraint races have separate MySQL Testcontainers coverage.
Use a new run ID each time so a previous run is not mistaken for a new test.

## Database evidence and interpretation

Run these read-only queries using your own database connection; no password or
database address is assumed. The services own separate databases: run each
block in the indicated database without cross-database joins.

**Event database, before and after each run:**

```sql
SET @event_id = 123;       -- replace with actual EVENT_ID
SET @ticket_type_id = 456; -- replace with actual TICKET_TYPE_ID
SELECT CURRENT_TIMESTAMP(6) AS observed_at, id, event_id,
       total_quantity, available_quantity
FROM ticket_types WHERE id = @ticket_type_id AND event_id = @event_id;
SELECT id, available_quantity FROM ticket_types WHERE available_quantity < 0;
```

**Booking database, inventory run:**

```sql
SET @run_id = 'replace-with-actual-run-id';
SET @event_id = 123;       -- same event ID
SET @ticket_type_id = 456; -- same ticket type
SELECT COUNT(DISTINCT b.id) AS confirmed_bookings,
       COALESCE(SUM(i.quantity), 0) AS confirmed_tickets
FROM bookings b JOIN booking_items i ON i.booking_id = b.id
WHERE b.idempotency_key LIKE CONCAT('k6-', @run_id, '-inventory-%')
  AND b.event_id = @event_id AND i.ticket_type_id = @ticket_type_id
  AND b.status = 'CONFIRMED';
```

Compare confirmed **ticket quantities**, not just booking count, with the
initial stock. With isolated traffic and successful payments:
`confirmed_tickets <= initial_stock` and
`final_available = initial_stock - confirmed_tickets >= 0`.
Reconcile discrepancies rather than calling the run successful.

**Booking database, idempotency run:**

```sql
SET @run_id = 'replace-with-actual-run-id';
SELECT id, user_id, booking_reference, event_id, status
FROM bookings WHERE idempotency_key = CONCAT('k6-', @run_id, '-idempotency');
SELECT user_id, idempotency_key, status, booking_id
FROM idempotency_records WHERE idempotency_key = CONCAT('k6-', @run_id, '-idempotency');
```

Expect one booking and one COMPLETED record for the authenticated user, linked
to the returned booking ID; stock should decrease by `QUANTITY` only once.
Multiple successful HTTP replay responses do not mean multiple bookings.

A final non-negative snapshot does **not** prove stock was never negative.
Sample the Event query during the run and retain timestamps for observations;
sampling can miss intermediate states. The atomic conditional update and
MySQL concurrency integration test provide stronger invariant evidence; do not
claim exhaustive history from k6 snapshots alone.

## Portfolio artifacts

Keep the run ID, commit SHA, k6 version, machine/service configuration, VUs,
attempts, quantity, initial stock, console output, generated summary JSON and
optional timestamped metrics JSON. Capture screenshots of:

1. Limited starting inventory and published future event.
2. Actual k6 status/check/threshold summary, including any failures.
3. Final stock plus confirmed-ticket totals for the same run.
4. Matching replay booking IDs, one stored booking and changed-body 409.

Redact JWTs, account details and credentials. Do not commit generated reports by
default. Record actual results only; these moderate local checks establish no
production-capacity claim.
