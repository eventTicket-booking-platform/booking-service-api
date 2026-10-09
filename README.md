# Booking Service API

Booking and booking-admin service for Event Hub. This service creates bookings, returns customer booking history, exposes admin booking views and stats, and provides internal booking counts for other services.

## Stack

- Java 17
- Spring Boot 3
- Spring Security OAuth2 resource server
- Spring Data JPA
- Spring Cloud Config client
- Eureka client
- OpenFeign
- MySQL
- RabbitMQ

## Functional Requirements

- Create bookings for authenticated users
- Return current user booking history
- Return booking detail for authorized user, admin, or host access
- Expose admin booking list with filters
- Expose booking statistics for admin dashboards
- Resolve event metadata from the event service
- Resolve user metadata from the auth service
- Publish notification events to RabbitMQ
- Return confirmed booking counts for internal consumers

## Non-Functional Requirements

- Stateless JWT authentication
- Role-aware admin access for management endpoints
- Config Server based configuration
- Eureka registration
- MySQL persistence
- RabbitMQ integration for async notification workflows
- Inter-service calls through Feign clients
- Actuator health support

## APIs

Base path: `/booking-service/api/v1`

Authenticated user endpoints:

- `POST /bookings`
- `GET /bookings/my`
- `GET /bookings/{bookingId}`

Admin or host endpoints:

- `GET /admin/all`
- `GET /admin/stats`

Internal endpoints:

- `GET /bookings/internal/event/{eventId}/count`

## Role-Based Access

- `permitAll`: `/bookings/internal/**`
- `admin`, `host`: `/admin/**`
- any authenticated user: booking creation and own booking views

Access to individual booking detail is finalized in service logic using the authenticated user ID and role.

## Runtime Dependencies

- Config Server on `8888`
- Eureka Server on `8761`
- MySQL
- RabbitMQ
- Event service
- Auth service

## Local Setup

1. Copy `.env.example` to `.env`.
2. Fill:
   - `SPRING_CLOUD_CONFIG_URI`
   - `BOOKING_DB_PASSWORD`
   - `RABBITMQ_PASSWORD`
3. Start:
   - `config-server`
   - `eureka-server`
   - MySQL
   - RabbitMQ
   - `auth-service-api`
   - `event-service-api`
4. Run:

```powershell
.\mvnw.cmd spring-boot:run
```

Default port: `9093`

## Build

```powershell
.\mvnw.cmd clean package
```

## Notes

- The current backend exposes create, list, and detail endpoints. A booking cancel endpoint is not present in this service code.

## Reservation expiry

Payment is simulated **synchronously**: CARD, WALLET, BANK_TRANSFER and CASH
immediately succeed; SIMULATED_FAIL fails. There is no asynchronous checkout or
payment completion endpoint, so no artificial pending payment window is added.

Previously, creation reserved inventory, saved the booking and payment, then
confirmed success. A failed payment immediately released inventory but left a
PENDING booking; a release exception rolled back that booking transaction and
could leave Event Service inventory reserved without a recovery record.

Now successful payment still immediately confirms, with no expiry timestamp.
Failed payment still attempts immediate release, using the booking reference as
the stable `releaseId` on the existing release endpoint. Successful compensation
sets CANCELLED with no expiry. If release throws, the existing
`noRollbackFor = PaymentFailedException` commits the failed payment and PENDING
booking, its exact BookingItem quantities and `reservationExpiresAt`; the request
still returns the existing payment-failure response and its idempotency record
is FAILED. Reusing that request key remains rejected.

Configure the positive ISO-8601 duration
`eventhub.booking.reservation-timeout=PT10M` or `BOOKING_RESERVATION_TIMEOUT`
(default ten minutes). It starts at failed-payment compensation and is stored
only while inventory release is outstanding. Every thirty seconds,
`ReservationExpiryScheduler` selects up to fifty expired PENDING bookings.
`eventhub.booking.reservation-expiry-poll-ms` can override that poll interval.
Each booking runs in its own transaction with a pessimistic row lock and a fresh
eligibility/payment check. CONFIRMED, CANCELLED and EXPIRED rows, missing/future
expiries and payments with SUCCESS, REFUNDED or a paidAt timestamp are untouched.
Release sends the persisted ticket IDs and quantities. Only after release
succeeds does the booking become EXPIRED and its timestamp clear. Failures leave
it eligible for later retry and do not prevent the remaining batch from running.

Event Service stores a release receipt and inventory increments in one local
transaction. Repeating the same release ID and quantities succeeds without
incrementing inventory again, including after a lost HTTP response or failed
Booking Service commit. Different quantities for that ID are rejected. Deploy
Event Service's release-id support **before** this Booking Service version.
Existing callers without a release ID remain compatible but have no deduplication.

With the existing `ddl-auto=update`, Hibernate adds nullable
`bookings.reservation_expires_at`; otherwise add a nullable DATETIME(6) column
before deployment. No timestamp is backfilled: old PENDING rows may already
have released inventory and cannot safely be inferred to hold reservations.
Event Service also needs its `ticket_releases` table (see its README).

This is failed-compensation recovery, not a distributed transaction. A crash or
database failure after remote reserve but before the local booking/payment
transaction commits can still leave an orphan reservation; this mechanism cannot
infer whether an unrecorded remote operation succeeded. Future asynchronous
payment completion must lock the booking and reject expired reservations before
charging. Current workers must have reasonably synchronized clocks. Database
locks span the release HTTP call, releases for one event serialize, and a batch
of fifty persistently failing rows can delay later rows. Release receipts must
be retained while a retry remains possible.

`ReservationExpiryTest` covers expiry, exact quantities, paid/confirmed guards,
repeat and concurrent expiry, real rollback/retry and configured-timeout creation
after failed compensation. Scheduler tests cover continuing after a failure.
Run `mvn test` with Java 17+; these persistence tests use H2, not production MySQL.

## Stale outbox recovery

A worker can crash after committing its outbox claim and leave an event in
`PROCESSING`. Before claiming pending events, `OutboxClaimService` resets rows
whose `processingStartedAt` is strictly older than the timeout to `PENDING` and
clears their claim timestamp. Recovery and pessimistic claiming run in the same
short `REQUIRES_NEW` transaction; RabbitMQ publication still runs outside it.
Fresh claims, claims with no timestamp, and other states are left unchanged.

Configure `eventhub.outbox.processing-timeout` (ISO-8601 duration), or set
`OUTBOX_PROCESSING_TIMEOUT`, for example `PT10M`. The default is ten minutes.
Use a positive duration longer than the normal time to process the **whole
batch**, including broker latency: the current batch contains up to 50 events
and each confirmation wait can take five seconds. Recovery runs on the existing
five-second publisher poll and logs the number of recovered rows. Retry counts
are preserved because an abandoned claim does not establish a publication failure.

The outbox row now has a JPA optimistic `version`. Recovery increments it so that
a late worker cannot overwrite a newer claim's state. A rejected stale save is
logged and the publisher continues with the remaining events. Event IDs,
correlation IDs, and payloads remain unchanged on recovery.

### Existing database rollout

Stop all booking-service workers before upgrading them together: old binaries
do not enforce the version check. The current `ddl-auto=update` configuration
adds the `version` column with default zero. If managing schema changes manually,
apply this once to the booking database before starting the upgraded workers:

```sql
ALTER TABLE outbox_events ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

### Validation and limits

Run from this service directory with Java 17+ and Maven:

```powershell
mvn test
```

`OutboxRecoveryTest` uses a real H2 database to check stale/fresh/boundary and
non-processing rows, configured-timeout reclaiming, repeat recovery, and rejection
of late worker writes. Existing publisher and two-worker tests cover the normal
publication flow; a publisher unit test checks continuation after a stale save.
MySQL/Testcontainers validation belongs to the planned integration-test phase.

This remains **at-least-once publication**, not exactly-once processing. A worker
may publish before crashing or before its claim expires, and recovery can publish
the same stable event ID again. Optimistic locking protects database state; it
cannot retract a RabbitMQ message. The consumer's duplicate handling remains
necessary, and an external email send followed by a crash before recording its
processed event can still produce duplicate emails. Claims with missing timestamps
require investigation rather than automatic recovery. Recovery relies on reasonably
synchronized worker clocks and scans matching rows without a bounded recovery batch.
