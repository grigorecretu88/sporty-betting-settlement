package com.sporty.settlement.integration;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.idempotency.ApiPublicationRepository;
import com.sporty.settlement.service.ApiPublicationClaimService;
import com.sporty.settlement.service.BetSettlementService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.outbox.dispatcher.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ClaimOwnershipAndSettlementConcurrencyIntegrationTest {
    @Autowired ApiPublicationClaimService apiClaims;
    @Autowired ApiPublicationRepository publications;
    @Autowired BetSettlementService settlements;
    @Autowired BetRepository bets;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void expiredApiClaimCannotCompleteOrReleaseNewOwner() {
        var original = apiClaims.claim("expiry-key", "hash");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publications.findLockedByKey("expiry-key").orElseThrow()
                        .reclaim(original.token(), Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> apiClaims.complete(original)).isInstanceOf(IllegalStateException.class);
        var replacement = apiClaims.claim("expiry-key", "hash");
        assertThat(replacement.token()).isNotEqualTo(original.token());
        assertThatThrownBy(() -> apiClaims.complete(original)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> apiClaims.release(original)).isInstanceOf(IllegalStateException.class);

        apiClaims.complete(replacement);
        assertThat(apiClaims.claim("expiry-key", "hash").status())
                .isEqualTo(ApiPublicationClaimService.Status.ALREADY_PUBLISHED);
    }

    @RepeatedTest(5)
    void concurrentSettlementHasOneTransitionAndOneDuplicate() throws Exception {
        BetSettlementCommandV1 command = new BetSettlementCommandV1("concurrent", "bet-1", "user-1",
                "event-100", "market-1", "team-a", new BigDecimal("10.00"), "team-a",
                Instant.now(), "corr");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<BetSettlementService.Result> work = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("start timeout");
            }
            return settlements.settle(command);
        };
        try {
            var first = executor.submit(work);
            var second = executor.submit(work);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            BetSettlementService.Result.SETTLED_WON,
                            BetSettlementService.Result.DUPLICATE);
            var settled = bets.findById("bet-1").orElseThrow();
            assertThat(settled.getOutcomeWinnerId()).isEqualTo("team-a");
            assertThat(settled.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
