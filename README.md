# Sports Betting Settlement Trigger Service

Spring Boot implementation of the Sporty Group Kafka & RocketMQ home assignment. A REST request publishes an event outcome to Kafka; the Kafka consumer atomically records the accepted result and settlement commands in H2; an outbox dispatcher sends those commands to RocketMQ; and a shared settlement service persists each bet as won or lost.

The default `mock` profile is fully executable with Kafka plus an in-process RocketMQ simulation. The optional `real` profile uses the supplied RocketMQ broker and the same settlement service; both transports have been exercised through final persisted bet states.

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

## Domain and policy

`Bet` preserves the assignment fields `betId`, `userId`, `eventId`, `eventMarketId`, `eventWinnerId`, and `betAmount` (`BigDecimal`). It also records `PENDING`, `SETTLED_WON`, or `SETTLED_LOST`, the accepted outcome winner, and settlement time.

The first committed outcome for an `eventId` is authoritative because the assignment defines neither versions nor corrections. The same Event ID and winner is a duplicate even if Event Name changes; the originally accepted name is retained. A different winner for the same event is detected, logged as a rejected business event, and acknowledged without creating settlement intents. It is not a transport failure to retry. No matching bets is a valid outcome and records zero intents.

A settlement command must match the persisted bet. The bet derives won/lost from its selection and the actual winner; callers cannot supply a contradictory win flag. The service locks that bet for the transition. Repeating the same outcome returns a duplicate result without mutation; a different outcome cannot overwrite final state. A missing bet or mismatched event/fields fails processing so the transport can retry or surface the failure.

## Optional real RocketMQ run

Stop the mock application before starting real mode. Start the brokers:

```bash
docker compose --profile real-rocketmq up -d
```

Run the application against the host-mapped name server:

```bash
./gradlew bootRun --args='--spring.profiles.active=real --rocketmq.name-server=localhost:9876'
```

On PowerShell use `./gradlew.bat` with the same arguments. Wait for startup and Kafka partition assignment, then use the API example above. The supplied broker advertises `localhost:10911`, so these instructions run the application on the Docker host.

Real publication and consumption were verified with broker 4.9.7, starter 2.3.5 and its 5.3.2 client: `SEND_OK` was followed by real consumer execution and persisted `SETTLED_WON`/`SETTLED_LOST` states. The image's JDK 8u372 can fail initializing container metrics on cgroup v2; Compose disables container detection and explicitly bounds its demonstration heap. Producer acknowledgement alone is not proof of settlement.

The default mock remains the simpler PDF-permitted producer fallback: it logs the serialized payload and delivers it through an in-process simulation. Its consumer is not an actual RocketMQ broker consumer.

## Delivery, transactions, and idempotency

- HTTP: `Idempotency-Key` is optional. A claim is stored before sending and marked `PUBLISHED` only after Kafka acknowledgement. A send failure attempts to release it without masking the original error. Acknowledgment followed by claim-completion failure returns 503 and leaves lease recovery available; it does not promise persistent HTTP exactly-once publication. If Kafka accepts the record but the client times out, retry may publish again; downstream outcome idempotency handles this ambiguity. Reusing the key with a different payload is rejected.
- Kafka: new groups start at the earliest available record, so outcomes published before initial assignment are not skipped. Seed bets initialize before listener startup. Existing group offsets still apply after an H2 restart. `eventId` is the record key. Record-mode acknowledgement follows listener success and therefore completion of the database transaction. Failures receive two bounded retries; an exhausted record is synchronously published to `event-outcomes.DLT`, and a failed DLT publication remains an error rather than being silently recovered. Kafka offset and H2 commit are not one atomic transaction; redelivery after a DB commit is therefore expected and safe.
- Matching transaction: the settlement-only outbox API requires an existing transaction. Accepted event identity and all required outbox rows commit or roll back together. The unique event ID and deterministic command/outbox IDs protect concurrent duplicates.
- Outbox: `PENDING -> PROCESSING -> SENT`, with `FAILED` after ten claims or an unsupported destination/message type. Every claim consumes an attempt, including expired claims with unknown publication outcomes. Malformed JSON uses the same bounded retry path. Claims have a 30-second lease; publication occurs outside the database transaction. The dispatcher claims one command immediately before sending, processing at most 50 commands within a five-second work window per invocation (an in-flight send may exceed it). Retry delay is capped at 60 seconds. An exhausted expired claim is marked terminal and ends that invocation; later work resumes on the next scan. A crash after broker acceptance but before `SENT` can republish. This is a single-dispatcher demonstration. Deployment-scale throughput is not established.
- Settlement: at-least-once delivery is safe because a locked pending bet can make one final transition and an identical redelivery is a no-op. This is business-effect idempotency, not globally exactly-once messaging.
- H2: the assignment-required database is in-memory. These guarantees hold during an application lifetime; bets, outcome identities, HTTP claims, and outbox messages are lost on a complete restart.

Outbox `FAILED` rows remain stored with attempt count and last error for diagnosis. This assignment does not implement automated replay of terminal failures.

## Failure behavior

Kafka publication failures return `503 EVENT_OUTCOME_PUBLISH_FAILED`; interrupted sends restore the thread flag. A matching failure rolls back the outcome and its outbox intents before Kafka retry. RocketMQ publication failure leaves the intent retryable; a settlement exception escapes the real listener or enters the mock's bounded retry/dead-letter path.

API errors retain correlation IDs and stable codes. Invalid requests receive client errors (including 405/415 for unsupported methods/content types); unexpected server diagnostics stay in logs. Kafka's container error handler logs each failed attempt with record metadata and retains exhausted records on the DLT.

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
- The real broker configuration targets a local host application and demonstrates delivery, not production durability or failover.
- Production operation would add durable storage, broker integration tests, metrics/alerts for pending/failed/lease age, controlled replay, schema evolution, authentication, and capacity-aware batch processing.
