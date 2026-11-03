package com.sporty.settlement.integration;

import com.sporty.settlement.repository.idempotency.ApiPublicationEntity;
import com.sporty.settlement.repository.idempotency.ApiPublicationRepository;
import com.sporty.settlement.exception.IdempotencyKeyConflictException;
import com.sporty.settlement.service.ApiPublicationClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false)
@Import(ApiPublicationClaimService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiPublicationClaimIntegrationTest {
    @Autowired ApiPublicationClaimService claims;
    @Autowired ApiPublicationRepository publications;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void resetDatabase() {
        publications.deleteAll();
    }

    @RepeatedTest(5)
    void concurrentNewClaimsHaveOneOwnerAndLoserCanReadInFreshTransaction() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<ApiPublicationClaimService.Claim> task = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            try {
                return claims.claim("concurrent-key", "hash");
            } catch (DataIntegrityViolationException collision) {
                // Same boundary as PublishEventOutcomeService: failed transaction has ended.
                return claims.claim("concurrent-key", "hash");
            }
        };
        try {
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(ApiPublicationClaimService.Claim::status)
                    .containsExactlyInAnyOrder(ApiPublicationClaimService.Status.CLAIMED,
                            ApiPublicationClaimService.Status.IN_PROGRESS);
            assertThat(publications.count()).isEqualTo(1);
            var owner = results.stream().filter(c -> c.status() == ApiPublicationClaimService.Status.CLAIMED)
                    .findFirst().orElseThrow();
            assertThat(publications.findById(owner.key()).orElseThrow().getClaimToken()).isEqualTo(owner.token());
            claims.complete(owner);
            assertThat(claims.claim(owner.key(), "hash").status())
                    .isEqualTo(ApiPublicationClaimService.Status.ALREADY_PUBLISHED);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void conflictingPayloadCannotReplaceInProgressOrPublishedClaim() {
        var owner = claims.claim("key", "first-hash");
        assertThatThrownBy(() -> claims.claim("key", "other-hash"))
                .isInstanceOf(IdempotencyKeyConflictException.class).hasMessageContaining("different payload");
        claims.complete(owner);
        assertThatThrownBy(() -> claims.claim("key", "other-hash"))
                .isInstanceOf(IdempotencyKeyConflictException.class).hasMessageContaining("different payload");
        var stored = publications.findById("key").orElseThrow();
        assertThat(stored.getPayloadHash()).isEqualTo("first-hash");
        assertThat(stored.getStatus()).isEqualTo(ApiPublicationEntity.Status.PUBLISHED);
    }

    @Test
    void expiredOwnerCannotCompleteOrReleaseBeforeOrAfterReclaim() {
        var original = claims.claim("key", "hash");
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                publications.findLockedByKey("key").orElseThrow()
                        .reclaim(original.token(), Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> claims.complete(original)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claims.release(original)).isInstanceOf(IllegalStateException.class);
        var replacement = claims.claim("key", "hash");
        assertThat(replacement.status()).isEqualTo(ApiPublicationClaimService.Status.CLAIMED);
        assertThat(replacement.token()).isNotEqualTo(original.token());
        assertThatThrownBy(() -> claims.complete(original)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claims.release(original)).isInstanceOf(IllegalStateException.class);
        claims.complete(replacement);
        assertThat(publications.findById("key").orElseThrow().getStatus())
                .isEqualTo(ApiPublicationEntity.Status.PUBLISHED);
    }

    @Test
    void releasedClaimAllowsRetryWithFreshOwnership() {
        var original = claims.claim("key", "hash");
        claims.release(original);
        assertThat(publications.findById("key")).isEmpty();
        var retry = claims.claim("key", "hash");
        assertThat(retry.token()).isNotEqualTo(original.token());
        assertThatThrownBy(() -> claims.complete(original)).isInstanceOf(IllegalStateException.class);
        claims.complete(retry);
        assertThat(claims.claim("key", "hash").status())
                .isEqualTo(ApiPublicationClaimService.Status.ALREADY_PUBLISHED);
    }
}
