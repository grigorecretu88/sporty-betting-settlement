# Sports Betting Settlement Trigger Service

**Kafka & RocketMQ -- Backend Home Assignment**

------------------------------------------------------------------------

## 1. Overview

This service simulates sports event outcome ingestion and bet settlement
using:

-   REST API
-   Apache Kafka
-   Apache RocketMQ (mockable)
-   H2 in-memory database
-   Transactional Outbox pattern
-   Idempotent message processing
-   Separate unit & integration test suites

The service implements the required event flow end-to-end.
The design keeps the components clearly separated and handles messaging in a
safe and reliable way, similar to what would be expected in a production system.

------------------------------------------------------------------------

# 2. Quick Start

## Prerequisites

-   Java 17+
-   Docker
-   Gradle wrapper (included)

------------------------------------------------------------------------

## 1️⃣ Start Kafka

``` bash
docker compose up -d
```

------------------------------------------------------------------------

## 2️⃣ Run the application

``` bash
./gradlew bootRun
```

Optional (explicit RocketMQ mock mode):

``` bash
./gradlew bootRun --args='--spring.profiles.active=mock'
```

------------------------------------------------------------------------

## Postman Collection

1. Import `/postman/sporty-settlement.postman_collection.json`
2. Ensure baseUrl = http://localhost:8080
3. Run:
    - 1 - List Bets (Before)
    - 2 - Publish Event Outcome
    - 3 - List Bets (After)

------------------------------------------------------------------------

## 3️⃣ Verify the system

List seeded bets:

``` bash
curl http://localhost:8080/api/bets
```

Publish an event outcome:

``` bash
curl -X POST http://localhost:8080/api/event-outcomes   -H 'Content-Type: application/json'   -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111'   -d '{"eventId":"E1","eventName":"Team A vs Team B","eventWinnerId":"W1"}'
```

Check bets again:

``` bash
curl http://localhost:8080/api/bets
```

Bets for `eventId = E1` should now be: - `SETTLED_WON` - `SETTLED_LOST`

------------------------------------------------------------------------

# 3. Architecture Overview

## High-Level Flow

``` text
Client
  │
  ▼
REST API (POST /api/event-outcomes)
  │
  ▼
Kafka Producer → topic: event-outcomes
  │
  ▼
Kafka Consumer
  │
  ▼
SettlementMatchingService
  │
  ▼
Transactional Outbox (H2)
  │
  ▼
RocketMQ Producer (real or mock)
  │
  ▼
RocketMQ Consumer
  │
  ▼
Bet Settlement persisted in DB
```

------------------------------------------------------------------------

## Core Processing Sequence

``` text
Client -> REST API: POST /api/event-outcomes
REST API -> Kafka: publish outcome (key=eventId)
Kafka -> EventOutcomeListener: deliver message
EventOutcomeListener -> DB: query PENDING bets
EventOutcomeListener -> DB: insert outbox settlement records
OutboxDispatcher -> RocketMQ: publish settlement message(s)
RocketMQ -> SettlementConsumer: deliver settlement
SettlementConsumer -> DB: update bet status
```

------------------------------------------------------------------------

# 4. Requirements Coverage

| Requirement | Where It Is Implemented |
|------------|-------------------------|
| Publish event outcome to Kafka | `POST /api/event-outcomes` → Kafka topic `event-outcomes` |
| Kafka consumer listens to `event-outcomes` | `EventOutcomeListener` |
| Match event outcome to bets | `SettlementMatchingService` |
| Produce settlement messages to RocketMQ | Transactional Outbox → `RocketProducer` (topic: `bet-settlements`) |
| RocketMQ consumer settles bets | `SettlementConsumer` |
| In-memory database | H2 (Spring Boot configuration) |
| RocketMQ mock allowed  | mock profile |




------------------------------------------------------------------------

# ️ 5. Data Model

## Bet

-   betId
-   userId
-   eventId
-   eventMarketId
-   eventWinnerId (predicted winner)
-   betAmount
-   status (PENDING, SETTLED_WON, SETTLED_LOST)
-   settledAt
-   outcomeWinnerId

## OutboxEvent

Used for reliable message publication:

-   id
-   aggregateId
-   type
-   payload
-   status (PENDING, SENT)
-   timestamps

------------------------------------------------------------------------

# 6. Reliability & Delivery Semantics

### Delivery Model

-   Kafka: at-least-once
-   RocketMQ: at-least-once

### Guarantees

-   Only PENDING bets are eligible for settlement
-   Settlement consumer is idempotent
-   Outbox prevents DB/MQ inconsistency

### Why Transactional Outbox?

Without outbox: DB update succeeds but MQ publish fails → inconsistent
state

With outbox: - Settlement intent is persisted - Publishing can be
retried safely - Crash recovery is deterministic

------------------------------------------------------------------------

# 7. Failure Scenarios

### Kafka message re-delivery

Safe because matching only processes PENDING bets.

### RocketMQ re-delivery

Safe because consumer uses idempotency tracking.

### Crash after DB write but before MQ publish

Safe because outbox retains unsent messages.

### Crash during settlement

Safe due to idempotent consumer logic.

------------------------------------------------------------------------

# 8. Testing

## Unit Tests

``` bash
./gradlew test
```

## Integration Tests (Embedded Kafka)

``` bash
./gradlew integrationTest
```

## Run All Checks

``` bash
./gradlew check
```

All tests are currently green.

------------------------------------------------------------------------

# 9. Configuration & Profiles

Profile       Purpose
  ------------- -------------------------
default       Normal runtime
mock          RocketMQ mocked
integration   Integration test tuning

------------------------------------------------------------------------

# 10. Possible Extensions

-   Dead-letter topics
-   Retry backoff strategies
-   Metrics (Micrometer)
-   Distributed tracing
-   Persistent DB (PostgreSQL)

------------------------------------------------------------------------

------------------------------------------------------------------------
# Bets API

## GET /api/bets

Returns the current list of bets.

### Response
`200 OK` with JSON array of `BetResponse` objects:

Fields:
- `betId` (string)
- `userId` (string)
- `eventId` (string)
- `eventMarketId` (string)
- `eventWinnerId` (string) — the user's predicted winner
- `betAmount` (number)
- `status` (string) — `PENDING`, `SETTLED_WON`, `SETTLED_LOST`
- `settledAt` (string | null) — ISO timestamp; null while `PENDING`
- `outcomeWinnerId` (string | null) — winner from the published event outcome; null while `PENDING`

### Notes
- On startup, seeded bets are typically `PENDING`, therefore `settledAt=null` and `outcomeWinnerId=null`.
- After publishing an event outcome, only bets matching that `eventId` are settled and updated.


------------------------------------------------------------------------
