# Architecture decisions

## Kafka for outcome ingestion

- **Problem:** outcome submission should not execute all matching and settlement work in the HTTP request.
- **Choice:** publish the three-field outcome to `event-outcomes`, keyed by `eventId`, and consume it in a separate adapter.
- **Why appropriate:** Kafka decouples ingestion from downstream work and naturally demonstrates redelivery behavior.
- **Trade-off:** Kafka acceptance is not final settlement, Kafka offsets cannot be atomically committed with this H2 transaction, and poison records move to a visible DLT after bounded retries.
- **Limitation:** the local stack is a single-broker demonstration, not a production Kafka topology.

## Transactional outbox

- **Problem:** a Kafka consumer must not record an outcome while losing the required RocketMQ settlement commands.
- **Choice:** store the accepted event identity and deterministic settlement outbox rows in one transaction. The outbox accepts only settlement commands and requires the caller transaction (`MANDATORY`); command identity and correlation come from that single input. Claim each committed row immediately before publishing outside that transaction, then record success separately. Drain at most 50 rows per scheduled run, checking a five-second work budget between sends. Completion failure after successful publication is logged separately and leaves lease recovery available.
- **Why appropriate:** RocketMQ availability is removed from the matching transaction while committed intent remains retryable.
- **Trade-off:** successful send followed by a crash before `SENT` produces a duplicate.
- **Limitation:** H2 is in-memory, so the outbox demonstrates transaction consistency but not durability across application restart.

## Business idempotency

- **Problem:** HTTP retries, Kafka redelivery, concurrent logical duplicates, and RocketMQ redelivery are normal.
- **Choice:** use broker-confirmed HTTP claims, unique accepted `eventId`, deterministic settlement command IDs, database constraints, and guarded final bet transitions. First committed outcome wins; the same Event ID and winner is a no-op even if the name changes (the original name remains) and conflicting winners are rejected.
- **Why appropriate:** each boundary protects the business invariant it actually owns without claiming global exactly-once delivery.
- **Trade-off:** an ambiguous broker timeout can still duplicate a transport message.
- **Limitation:** outcome corrections require a future versioned contract and reconciliation policy.

## In-memory H2

- **Problem:** the assignment explicitly requires an in-memory bet database.
- **Choice:** use H2 for bets, accepted outcomes, idempotency claims, and outbox state.
- **Why appropriate:** it keeps the demonstration self-contained and permits real database transactions and constraints.
- **Trade-off:** it does not model production persistence, volume, or failover.
- **Limitation:** all state disappears after a complete process restart.

## Real and mock RocketMQ adapters

- **Problem:** local RocketMQ setup is optional in the assignment, but the integration boundary matters for the target role.
- **Choice:** keep both adapters behind one `BetSettlementPublisher` boundary and route both consumers to `BetSettlementService`. Mock mode is default; real mode requires synchronous `SEND_OK`.
- **Why appropriate:** local execution remains simple while transport-specific behavior stays explicit and testable.
- **Trade-off:** the mock cannot reproduce broker storage, acknowledgements, rebalancing, or restart behavior.
- **Verification:** broker 4.9.7 and the starter's 5.3.2 client delivered commands through the real consumer and persisted final bet states. The original failure was the broker image's JDK 8u372 cgroup-v2 metrics initialization, corrected by disabling container detection with an explicitly bounded local heap.
- **Limitation:** the broker advertises localhost for the documented host application; this is a local demonstration configuration.

## Deliberately limited infrastructure

- **Problem:** production messaging systems invite tracing, DLQ administration, schema registries, durable databases, and extensive operational tooling.
- **Choice:** retain only correlation IDs, focused logs, bounded retries, terminal failure visibility, and Actuator already present in the project.
- **Why appropriate:** it demonstrates the required failure semantics without turning a short assignment into a platform.
- **Trade-off:** production operators would need metrics, alerts, controlled replay, security, and capacity planning.
- **Limitation:** terminal outbox rows retain bounded diagnostics; mock terminal payloads remain in JVM memory with a count and error log. Neither has an administrative inspection/replay API.
