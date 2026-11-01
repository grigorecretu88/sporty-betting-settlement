# Sports Betting Settlement Trigger Service

Spring Boot implementation of the Sporty Group Kafka & RocketMQ home assignment. A REST request publishes an event outcome to Kafka; the Kafka consumer atomically records the accepted result and settlement commands in H2; an outbox dispatcher sends those commands to RocketMQ; and a shared settlement service persists each bet as won or lost.

The default `mock` profile is fully executable with Kafka plus an in-process RocketMQ simulation. The optional `real` profile retains adapters that target the same application services, with the runtime limitation disclosed below.

## Architecture

```text
POST /api/event-outcomes
        |
        | bounded wait for Kafka broker acknowledgement
        v
Kafka: event-outcomes  -- correlation ID header -->  EventOutcomeListener
                                                        |
                                            one H2 transaction
                               +------------------------+------------------+
                               | accepted outcome (event_id unique)        |
                               | PENDING outbox commands (one per bet)     |
                               +------------------------+------------------+
                                                        |
                                       lease/claim transaction, then publish
                                                        v
RocketMQ: bet-settlements (real or in-process mock)
                                                        |
                                                        v
                    BetSettlementService -- lock/guard --> H2 bet status
```

Responsibilities remain explicit:

- API: validation, HTTP contract, idempotency header, error mapping.
- Kafka adapter: keyed, broker-confirmed publication and transport consumption.
- Application services: transactional matching and the settlement state transition.
- Persistence: bets, accepted outcomes, HTTP publication claims, and outbox records.
- RocketMQ adapters: real/mock transport only; both call `BetSettlementService`.

See [Architecture decisions](docs/architecture-decisions.md) for the concise rationale and trade-offs.

## Domain and policy

`Bet` preserves the assignment fields `betId`, `userId`, `eventId`, `eventMarketId`, `eventWinnerId`, and `betAmount` (`BigDecimal`). It also records `PENDING`, `SETTLED_WON`, or `SETTLED_LOST`, the accepted outcome winner, and settlement time.

The first committed outcome for an `eventId` is authoritative because the assignment defines neither versions nor corrections. An exact repeat is harmless. A different winner for the same event is detected, logged as a rejected business event, and acknowledged without creating settlement intents. It is not a transport failure to retry. No matching bets is a valid outcome and records zero intents.

A settlement command must match the persisted bet. The service locks that bet for the transition. Repeating the same outcome returns a duplicate result without mutation; a different outcome cannot overwrite final state. A missing bet or mismatched event/fields fails processing so the transport can retry or surface the failure.

## Prerequisites

- Java 17 or newer (verified locally with Java 21)
- Docker with Compose for local Kafka
- The included Gradle wrapper; no system Gradle installation is required

## Default local run (mock RocketMQ)

Start Kafka only:

```bash
docker compose up -d zookeeper kafka
```

Run the application; `mock` is the default profile:

```bash
./gradlew bootRun
```

On Windows PowerShell use `./gradlew.bat bootRun`.

Seeded bets are:

| Bet | Event | Predicted winner | Amount | Initial status |
|---|---|---|---:|---|
| `bet-1` | `event-100` | `team-a` | 10.00 | `PENDING` |
| `bet-2` | `event-100` | `team-b` | 25.00 | `PENDING` |
| `bet-3` | `event-200` | `team-x` | 5.00 | `PENDING` |

Inspect state:

```bash
curl http://localhost:8080/api/bets
```

Publish the acceptance scenario:

```bash
curl -i -X POST http://localhost:8080/api/event-outcomes \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: derby-result-1' \
  -H 'X-Correlation-Id: demo-correlation-1' \
  -d '{"eventId":"event-100","eventName":"Derby","eventWinnerId":"team-a"}'
```

The API returns `202 Accepted` only after Kafka acknowledges the send. Poll `GET /api/bets`: `bet-1` becomes `SETTLED_WON`, `bet-2` becomes `SETTLED_LOST`, and `bet-3` remains `PENDING`.

The in-process RocketMQ simulation serializes messages, delivers them on a consumer thread, and retries a failed delivery up to five total attempts, with 100/200/300/400 ms retry delays, before retaining it in an in-memory dead-letter collection. It does not simulate broker persistence, acknowledgements, or restart durability.

## Experimental real RocketMQ adapter

The `real` profile retains a producer and consumer adapter for investigation. It is **unsupported** for the submission demonstration: a previous broker run observed producer `SEND_OK`, but the consumer did not settle the published bets. Use the default mock mode, which follows the PDF's permitted fallback and logs the serialized settlement payload. Do not infer real consumer success from producer acknowledgement.

## Delivery, transactions, and idempotency

- HTTP: `Idempotency-Key` is optional. A claim is stored before sending and marked `PUBLISHED` only after Kafka acknowledgement. A send failure attempts to release it without masking the original error. Acknowledgment followed by claim-completion failure returns 503 and leaves lease recovery available; it does not promise persistent HTTP exactly-once publication. If Kafka accepts the record but the client times out, retry may publish again; downstream outcome idempotency handles this ambiguity. Reusing the key with a different payload is rejected.
- Kafka: `eventId` is the record key. Record-mode acknowledgement follows listener success and therefore completion of the database transaction. Failures receive two bounded retries; an exhausted record is synchronously published to `event-outcomes.DLT`, and a failed DLT publication remains an error rather than being silently recovered. Kafka offset and H2 commit are not one atomic transaction; redelivery after a DB commit is therefore expected and safe.
- Matching transaction: accepted event identity and all required outbox rows commit or roll back together. The unique event ID and deterministic command/outbox IDs protect concurrent duplicates.
- Outbox: `PENDING -> PROCESSING -> SENT`, with `FAILED` after ten attempts or an unsupported destination/message type. Malformed JSON follows the bounded retry path before becoming `FAILED`. Claims have a 30-second lease; the dispatcher claims one command immediately before each send, draining at most 50 commands per scheduler invocation within a five-second work window (an in-flight send may exceed that window). Fast sends therefore avoid the former two-command/second ceiling; slow sends remain bounded by transport latency. This is a single-dispatcher demonstration, not a multi-instance throughput claim. Retry delay is capped at 60 seconds. Publication happens outside a database transaction. A crash after RocketMQ accepts but before `SENT` can republish.
- Settlement: at-least-once delivery is safe because a locked pending bet can make one final transition and an identical redelivery is a no-op. This is business-effect idempotency, not globally exactly-once messaging.
- H2: the assignment-required database is in-memory. These guarantees hold during an application lifetime; bets, outcome identities, HTTP claims, and outbox messages are lost on a complete restart.

Outbox `FAILED` rows remain stored with attempt count and last error for diagnosis. This assignment does not implement automated replay of terminal failures.

## Failure behavior

- Kafka serialization, broker, timeout, async-send, and interruption failures become `503 EVENT_OUTCOME_PUBLISH_FAILED`; interruption restores the thread flag.
- A matching failure rolls back both processed-outcome identity and outbox intents. Kafka is allowed to redeliver.
- A persistently failing or poison Kafka record is retained on `event-outcomes.DLT` after two retries; DLT publication failure is propagated.
- RocketMQ publication failure returns the outbox row to `PENDING`; it is never marked `SENT` first.
- A process crash after successful RocketMQ send may duplicate a command; final settlement is idempotent.
- A settlement exception escapes the real RocketMQ listener. Mock mode requeues it and makes terminal simulated failures visible in its in-memory dead-letter collection.

## Tests

```bash
./gradlew test
./gradlew integrationTest
./gradlew clean check
```

Unit tests cover validation, API failure mapping, Kafka async failure/timeout, logical duplicate/conflict policy, outbox publication ordering/retry, and settlement transition guards. Integration tests use embedded Kafka and H2 to prove the complete HTTP-to-final-bet-state flow, repeat stability, poison-record routing to the DLT, atomic rollback of outcome plus outbox, concurrent equivalent-outcome behavior, and persistence-level outbox lease/retry/terminal states. Awaitility provides bounded asynchronous assertions; there are no fixed sleeps.

## Known limitations

- In-memory H2 has no restart durability and is not representative of a shared production database.
- The first-outcome-wins policy needs an explicit version/correction model in a real sportsbook.
- The outbox claim algorithm is designed for a shared transactional database, but horizontal multi-instance behavior has not been load-tested here.
- Kafka and H2 are not atomically committed, and RocketMQ delivery is at least once.
- The mock RocketMQ queue/dead letters exist only in this JVM.
- Real RocketMQ startup and producer acknowledgement were runtime-verified against the supplied 4.9.7 broker. Consumption did not drain the published queue with the starter's 5.3.2 client, so real-mode end-to-end settlement is not verified.
- Production operation would add durable storage, broker integration tests, metrics/alerts for pending/failed/lease age, controlled replay, schema evolution, authentication, and capacity-aware batch processing.
