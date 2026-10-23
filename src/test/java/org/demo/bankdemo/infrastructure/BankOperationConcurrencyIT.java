package org.demo.bankdemo.infrastructure;

import com.redis.testcontainers.RedisContainer;
import org.demo.bankdemo.account.*;
import org.demo.bankdemo.currency.*;
import org.demo.bankdemo.transfer.*;
import org.demo.bankdemo.user.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Runs only in the integration-tests profile, against disposable PostgreSQL and Redis. */
@SpringBootTest(properties = {
        "app.transfer.scheduler-delay-ms=3600000",
        "app.exchange.quote-cleanup-delay-ms=3600000"
})
@ActiveProfiles("test")
class BankOperationConcurrencyIT {
    private static final PostgreSQLContainer POSTGRES;
    private static final RedisContainer REDIS;
    static {
        POSTGRES = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"));
        REDIS = new RedisContainer(DockerImageName.parse("redis:6.2.6"));
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '1s'");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @AfterAll
    static void stop() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired AccountNumberGenerator numbers;
    @Autowired TransferService transfers;
    @Autowired ScheduledTransferService schedules;
    @Autowired ExchangeService exchanges;
    @Autowired CurrencyBalanceRepository balances;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private record Client(String email, Long accountId, String accountNumber) {}
    private Client client() {
        var email = UUID.randomUUID() + "@example.invalid";
        var user = users.saveAndFlush(new User(email, "synthetic-hash", "Test", "Client", null));
        var account = accounts.saveAndFlush(new Account(user, numbers.generateUnique(), new BigDecimal("1000.00")));
        return new Client(email, account.getId(), account.getAccountNumber());
    }

    @Test
    void concurrentTransferRetryCommitsExactlyOneDebit() throws Exception {
        var source = client();
        var destination = client();
        var command = new TransferCommand(UUID.randomUUID().toString(), "Test Client", destination.accountNumber(),
                new BigDecimal("125.00"), "Concurrent retry");
        var results = parallel(() -> transfers.execute(source.email(), command),
                () -> transfers.execute(source.email(), command));
        assertThat(results.get(0).referenceNumber()).isEqualTo(results.get(1).referenceNumber());
        assertThat(balance(source)).isEqualByComparingTo("875.00");
        assertThat(balance(destination)).isEqualByComparingTo("1125.00");
        assertThat(jdbc.queryForObject("select count(*) from bank_transactions where idempotency_key = ?",
                Integer.class, command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void oppositeDirectionTransfersUseConsistentLockOrder() throws Exception {
        var a = client();
        var b = client();
        parallel(() -> transfers.execute(a.email(), new TransferCommand(UUID.randomUUID().toString(),
                        "Test Client", b.accountNumber(), new BigDecimal("10.00"), "A to B")),
                () -> transfers.execute(b.email(), new TransferCommand(UUID.randomUUID().toString(),
                        "Test Client", a.accountNumber(), new BigDecimal("10.00"), "B to A")));
        assertThat(balance(a)).isEqualByComparingTo("1000.00");
        assertThat(balance(b)).isEqualByComparingTo("1000.00");
    }

    @Test
    void concurrentScheduleCreationPersistsOneSchedule() throws Exception {
        var source = client();
        var destination = client();
        var key = UUID.randomUUID().toString();
        var first = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        Callable<Boolean> create = () -> {
            schedules.create(source.email(), key, destination.accountNumber(), "Test Client",
                    new BigDecimal("125.00"), "Concurrent schedule", ScheduleFrequency.DAILY, first);
            return true;
        };
        parallel(create, create);
        assertThat(jdbc.queryForObject("select count(*) from scheduled_transfers where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(balance(source)).isEqualByComparingTo("1000.00");
    }

    @Test
    void concurrentExchangeRetryCommitsOneDebitAndOneForeignCredit() throws Exception {
        var source = client();
        var quote = exchanges.prepareQuote(source.email(), CurrencyCode.PLN, CurrencyCode.EUR,
                new BigDecimal("100.00"));
        var results = parallel(() -> exchanges.executePrepared(source.email(), quote.idempotencyKey()),
                () -> exchanges.executePrepared(source.email(), quote.idempotencyKey()));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(balance(source)).isEqualByComparingTo("900.00");
        assertThat(balances.findByAccountIdAndCurrency(source.accountId(), CurrencyCode.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo(quote.quote().targetAmount());
        assertThat(jdbc.queryForObject("select count(*) from exchange_transactions where idempotency_key = ?",
                Integer.class, quote.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void accountLockTimesOutWithoutDebitAndSameOperationCanBeRetried() throws Exception {
        var source = client();
        var quote = exchanges.prepareQuote(source.email(), CurrencyCode.PLN, CurrencyCode.EUR,
                new BigDecimal("100.00"));
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                accounts.findByOwnerEmailForUpdate(source.email()).orElseThrow();
                acquired.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock test timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                var started = System.nanoTime();
                assertThatThrownBy(() -> exchanges.executePrepared(source.email(), quote.idempotencyKey()))
                        .isInstanceOf(TransientDataAccessException.class);
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(8);
                assertThat(balance(source)).isEqualByComparingTo("1000.00");
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        exchanges.executePrepared(source.email(), quote.idempotencyKey());
        assertThat(balance(source)).isEqualByComparingTo("900.00");
    }

    private BigDecimal balance(Client client) {
        return accounts.findById(client.accountId()).orElseThrow().getBalance();
    }

    private static <T> List<T> parallel(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return first.call(); });
            var b = pool.submit(() -> { ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return second.call(); });
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("workers not ready");
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}
