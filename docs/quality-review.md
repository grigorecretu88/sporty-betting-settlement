# Final engineering review — 2026-11-02

## Assessment and scope

Ready for a rigorous Java Team Lead review, subject to the explicit assignment-level limitations below. This is a strong implementation, not a claim of production readiness or zero defects. The original three-page PDF was read from the local Downloads directory. Its five use cases, six bet fields, named topics, in-memory database, and executable instructions are implemented. Public repository delivery is intentionally outside this change: no account/default-branch changes, publication, push, or submission was performed.

The read-only initial review covered production and test classes, application/test profiles, build and wrapper configuration, Compose, broker configuration, logging, README, architecture decisions, Postman, and relevant history. The starting tree was clean on `test` at `6d57c7bbfd3630dc82631c99c8f52da1c4faacdc`.

## Architecture assessment

Retain the API ? Kafka ? transactional matching ? outbox ? RocketMQ ? settlement flow. Packages are navigable and responsibilities are proportionate: API handles HTTP, messaging handles transport, services coordinate transactions, entities own persisted state, repositories provide queries/locking, and configuration/logging stay explicit. No package relocation or additional layer is warranted. The query service's small dependency on the response DTO is acceptable here; another mapper layer would add little value.

Retain HTTP claim ownership/hash/lease recovery, accepted-outcome uniqueness, deterministic command identity, outbox claim ownership and attempt budgets, and locked settlement. These address different failure boundaries. Matching and outbox persistence share a transaction; broker sends occur outside database transactions; claim completion occurs in a separate proxied bean. `REQUIRES_NEW` does not split matching from its intents. Nullable entity versions also ensure assigned-ID records are inserted rather than accidentally merged; versioning protects stale updates alongside claim selection locks. Seed initialization precedes messaging lifecycle startup.

Correct the domain's redundant win flag and raw API diagnostic exposure. Simplify the generic outbox API. Remove only its arbitrary-object/destination overloads, not an architectural component. Principal regression risks were transaction interception, command identity/correlation preservation, and HTTP contracts; the tests and runtime checks below address them.

## Changes and verification

| Previous issue | Change and reason | Regression evidence |
|---|---|---|
| Caller could supply a win flag inconsistent with the actual winner | `Bet.markSettled` derives final status from selection and outcome | Direct domain tests for won/lost, unchanged duplicate timestamp, conflict and invalid input; service/concurrency tests; both runtime paths |
| Generic outbox accepted unsupported objects/destinations and independently supplied identity/correlation | One settlement-command input; identity/correlation derived from it; `MANDATORY` enforces caller transaction | Serialization/persistence tests, rejection outside a transaction, existing atomic rollback and complete flow tests |
| API returned raw internal exception text and logged expected validation failures with stacks | Stable safe server/malformed-JSON messages; client rejection logs without stacks; server failures retain diagnostics | HTTP error-body and correlation tests, unexpected database-error test |
| Generic handler converted Spring MVC protocol failures to 500 | Preserve Spring `ErrorResponse` status and headers | 405 plus `Allow: POST`, 415, and no publication invocation |
| Documentation did not describe refined contracts | README and architecture decision updated | Compared against implementation and observed runtime |

Existing business and reliability tests remain. The obsolete blank-destination test was removed because callers can no longer supply a destination; the persistence test still asserts `bet-settlements`. Generic payload fixtures were replaced with actual settlement commands and deterministic-ID assertions. No assertions were weakened to conceal failures.

## Complexity and configuration

Necessary complexity: transaction boundaries, outbox retries/leases, business idempotency, and shared real/mock settlement behavior. Optional complexity retained: HTTP publication claims and the delayed mock retry/dead-letter simulation. Both are already bounded, tested, and useful; replacing them would risk weaker guarantees for little reviewer benefit. No generic event infrastructure, new production dependency, framework upgrade, or configuration abstraction was added.

Configuration required no material correction. Default mock execution, real profile opt-in, topic/group settings, broker address override, Kafka retry/acknowledgment timeouts, and test-only dispatcher controls remain intentional. The outbox destination is the logical settlement route; the real adapter resolves its configured broker topic. Preserve the broker's verified JDK/container compatibility setting. Test contexts use embedded Kafka and isolated H2 state. The repository barrier test deliberately forces two real absent-row reads in distinct transactions; retain it.

## Execution evidence

Executed with the Windows wrapper and local Java 21, compiling Java with release 17:

- Initial `test integrationTest --no-build-cache` was up-to-date and was NOT counted as a fresh baseline.
- Baseline `clean test integrationTest --no-build-cache`: successful, 50 unit and 33 integration executions.
- Focused `test --no-build-cache`: successful after the initial refactors.
- `clean test --no-build-cache`: successful.
- `integrationTest --no-build-cache`: successful after the outbox/domain changes, including the new transaction requirement.
- `clean check --no-build-cache -I test-output/coverage.gradle`: Gradle reported BUILD SUCCESSFUL; 55 unit and 34 integration executions; combined JaCoCo report generated.
- Final plain `clean check --no-build-cache`: BUILD SUCCESSFUL in 1m 9s, exit code 0, all 8 tasks executed; 55 unit tests and 34 integration executions, zero failures/errors.

The three concurrency scenarios use five repetitions each (HTTP claims, outcome acceptance, settlement) and ran across baseline and successive full integration runs. No fixed sleeps were introduced. Coverage bootstrap initially failed twice due to temporary Gradle init-script configuration, then succeeded; these were tooling failures, not application test failures. PowerShell's redirected native stderr yielded a nonzero wrapper status on the coverage invocation despite Gradle's successful build and generated reports; the plain final command independently verifies the build.

Combined automated coverage: **602/669 lines (90.0%)**, **135/192 branches (70.3%)**. Application services: 228/240 lines and 68/78 branches. All production classes are included, including real adapters, whose 27 lines and 14 branches are uninstrumented by the automated mock-oriented suite. Runtime smoke checks are separate evidence, not added to those percentages. The temporary init script and copied report are under ignored `test-output/`; no coverage dependency was added to the project.

Important coverage includes duplicate/conflicting outcomes, changed event name, no bets, mismatched/missing bets, concurrent acceptance/settlement, transaction rollback, Kafka timeout/async failure and poison DLT routing, outbox retries/expiry/stale owners/exhaustion/send-success-update-failure, and mock retry counts/dead letters/shutdown/MDC isolation. Remaining gaps include an automated real-broker suite, explicit DLT-send failure injection, and some interruption/error branches. These constrain the testing score; they do not justify claiming complete failure coverage.

## Runtime evidence

Both runs used the supplied Docker stack. Mock mode used the default profile with an isolated Kafka consumer group. It began with all bets pending, accepted the example POST with 202, and exposed won/lost/pending through `GET /api/bets`.

Real mode used `--spring.profiles.active=real --rocketmq.name-server=localhost:9876` and dedicated Kafka/RocketMQ consumer groups. An initial run replayed existing broker history; it was not treated as proof of a fresh request. Restarting with the advanced group offsets produced three pending bets. The fresh request with correlation `quality-real-fresh` received Kafka acknowledgment at partition 0, offset 13. Real RocketMQ acknowledgments were followed by settlement on `ConsumeMessageThread_quality-real-20261102_1` and `_2`, retaining that correlation ID.

| Bet | Mock final state | Real final state |
|---|---|---|
| bet-1 | SETTLED_WON | SETTLED_WON |
| bet-2 | SETTLED_LOST | SETTLED_LOST |
| bet-3 | PENDING | PENDING |

Logs and initial/final API JSON are retained locally in ignored `test-output/`. Applications launched for verification were stopped; pre-existing broker containers were left running. Broker history/consumer offsets naturally include these verification messages.

## Independent adversarial review

A separate read-only reviewer found one medium-severity pre-existing issue: `ApiExceptionHandler.handleUnexpected` misclassified framework HTTP errors as 500. The correction preserves framework status/headers and adds 405/415 tests. The reviewer rechecked the correction, observed passing API tests, and confirmed resolution. It found no additional material defect in the current refinements, transaction/outbox design, concurrency protections, or messaging separation. This is an independent review pass, not a proof of absence of defects.

## Quality evaluation and remaining limits

| Dimension | Score | Reason |
|---|---:|---|
| Requirements | 9.5/10 | Functional PDF requirements verified; final public-repository delivery intentionally deferred |
| Architecture | 9/10 | Clear event flow and justified transaction boundaries; HTTP claims remain optional complexity |
| Domain modeling | 9/10 | Entity derives final state and guards redelivery; construction assumes controlled seed creation |
| Java/Spring correctness | 9/10 | Effective proxy boundaries and runtime evidence; no claim of production-scale concurrency proof |
| Cleanliness/maintainability | 9/10 | Focused components and narrower contracts; some existing compact formatting remains |
| Reliability | 9/10 | Meaningful recovery and idempotency tested within one application lifetime |
| Configuration/documentation | 9/10 | Reproduced supported paths, explicit limitations, no gratuitous configuration churn |
| Testing | 8.5/10 | Strong database/concurrency/failure tests; real adapters and several failure branches lack automation |
| Engineering judgment | 9/10 | Small refinements preserve proven behavior and stop before diminishing returns |
| Overall | 9/10 | Ready for demanding assignment review within stated scope |

No known material correctness defect remains from these reviews. Intentional limits remain: volatile H2/queues, first-outcome-wins without corrections, at-least-once delivery, no automatic terminal replay, and no multi-instance load/failover proof. No production-grade durability or globally exactly-once claim is made. Further structural refactoring would add more regression risk than practical benefit.

## Git state

Branch remains `test`; HEAD remains `6d57c7bbfd3630dc82631c99c8f52da1c4faacdc`. Changes are uncommitted and reviewable. No push, merge, publish, or submission occurred.

Changed files (relative to repository root):

```text
 M README.md
 M docs/architecture-decisions.md
 M src/main/java/com/sporty/settlement/api/ApiExceptionHandler.java
 M src/main/java/com/sporty/settlement/domain/Bet.java
 M src/main/java/com/sporty/settlement/service/BetSettlementService.java
 M src/main/java/com/sporty/settlement/service/OutboxService.java
 M src/main/java/com/sporty/settlement/service/SettlementMatchingService.java
 M src/test/integration/java/com/sporty/settlement/integration/TransactionalConsistencyIntegrationTest.java
 M src/test/unit/java/com/sporty/settlement/api/BetsControllerTest.java
 M src/test/unit/java/com/sporty/settlement/api/EventOutcomeControllerTest.java
 M src/test/unit/java/com/sporty/settlement/service/BetSettlementServiceTest.java
 M src/test/unit/java/com/sporty/settlement/service/OutboxServiceTest.java
 M src/test/unit/java/com/sporty/settlement/service/SettlementMatchingServiceTest.java
?? docs/quality-review.md
?? src/test/unit/java/com/sporty/settlement/domain/BetTest.java
```
