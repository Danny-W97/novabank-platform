package org.demo.bankdemo.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.zaxxer.hikari.HikariDataSource;
import org.demo.bankdemo.account.AccountNumberGenerator;
import org.demo.bankdemo.transfer.ScheduleFrequency;
import org.demo.bankdemo.transfer.ScheduledTransferService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

/** Opt-in bounded HTTP workload, never an existing DEV database. Results are not production SLO certification. */
@EnabledIfSystemProperty(named = "novabank.load.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "management.server.port=-1",
        "app.transfer.scheduler-delay-ms=250", "app.exchange.quote-cleanup-delay-ms=3600000",
        "app.transfer.rate-limit-per-minute=100000", "app.retention.enabled=false",
        "app.rate-limit.transfer-ip-per-window=100000", "app.rate-limit.transfer-user-per-window=100000",
        "spring.datasource.hikari.maximum-pool-size=12",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '3s'"
})
@ActiveProfiles("test")
class ConcurrentTransferLoadIT {
    private static final int CLIENTS = 16, DURATION_SECONDS = 120, INITIAL_BALANCE = 100000;
    private static final int P95_LIMIT_MS = 2000, P99_LIMIT_MS = 5000;
    private static final String PASSWORD = "SyntheticLoadPassword9!";
    private static final Path REPORT = Path.of("target/cc-transfer-load-report.json");
    private static PostgreSQLContainer postgres;
    private static RedisContainer redis;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        // Containers are created only for this opt-in test; no endpoint comes from CC/env input.
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        redis = new RedisContainer(DockerImageName.parse("redis:7.4"));
        try { postgres.start(); redis.start(); }
        catch (RuntimeException error) { if (redis != null) redis.stop(); postgres.stop(); throw error; }
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", redis::getFirstMappedPort);
    }
    @AfterAll static void cleanup() { if (redis != null) redis.stop(); if (postgres != null) postgres.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountNumberGenerator numbers;
    @Autowired ScheduledTransferService schedules;
    @Autowired HikariDataSource pool;
    @Autowired PlatformTransactionManager transactions;
    private final List<Map<String,Object>> operations = new CopyOnWriteArrayList<>();
    private record Client(long owner, long account, String number, HttpClient http, String csrf) { }

    @Test @Timeout(value = 360)
    void simultaneousHttpTransfersAndReplaysPreserveLedgerAndReportResources() throws Exception {
        var report = new LinkedHashMap<String,Object>();
        report.put("format", 1); report.put("generatedAt", Instant.now().toString());
        report.put("scope", "isolated PostgreSQL 18 + Redis 7.4, real bank HTTP; 16 clients for 120 seconds + replays + 1 scheduled transfer");
        report.put("acceptance",Map.of("clients",CLIENTS,"durationSeconds",DURATION_SECONDS,"p95LimitMillis",P95_LIMIT_MS,"p99LimitMillis",P99_LIMIT_MS,"technicalErrors",0,"scope","DEV_TEST_ONLY"));
        report.put("resourceContainers",Map.of("postgres",postgres.getContainerId(),"redis",redis.getContainerId(),"samplingSeconds",2));
        report.put("limitations", List.of("CPU/RAM include the HTTP generator in the same JVM", "host-visible metrics may be constrained by CI/WSL cgroups",
                "DEV/TEST acceptance only, not production capacity certification", "synthetic clients have no configured PIN/2FA and use saved recipients"));
        report.put("operations", operations); report.put("integrityPassed", false);
        var metrics = new LinkedHashMap<String,Object>(); report.put("metrics", metrics);
        var sampler = Executors.newSingleThreadScheduledExecutor();
        var resourceSampler=Executors.newSingleThreadScheduledExecutor();
        var resourceSamples=new CopyOnWriteArrayList<Map<String,Object>>();report.put("resourceSamples",resourceSamples);
        var peakActive = new AtomicLong(); var peakWaiting = new AtomicLong(); var peakLocks = new AtomicLong();
        var peakHeap = new AtomicLong(); var peakRss = new AtomicLong(); var samples = new AtomicLong(); var sampleErrors = new AtomicLong();
        var os = ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : -1;
        long start = System.nanoTime();
        try {
            var clients = new ArrayList<Client>();
            for (int i = 0; i < CLIENTS; i++) clients.add(seed());
            for (int i = 0; i < CLIENTS; i++) {
                var source = clients.get(i); var destination = clients.get((i+1)%CLIENTS);
                jdbc.update("INSERT INTO saved_recipients(owner_id,alias,recipient_name,account_number,created_at,updated_at) VALUES (?,'Load recipient','Load Recipient',?,now(),now())",
                        source.owner(), destination.number());
            }
            var beforeDeadlocks = jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()", Long.class);
            var due = Instant.now().plusSeconds(1);
            var sourceEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, clients.getFirst().owner());
            var schedulerTitle = "Load scheduler "+UUID.randomUUID();
            schedules.create(sourceEmail, UUID.randomUUID().toString(), clients.get(1).number(), "Load Recipient",
                    BigDecimal.ONE, schedulerTitle, ScheduleFrequency.DAILY, due);
            sampler.scheduleAtFixedRate(() -> {
                try {
                    peakActive.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    peakWaiting.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                    peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                    if (Files.isReadable(Path.of("/proc/self/status"))) {
                        var line = Files.readAllLines(Path.of("/proc/self/status")).stream().filter(s -> s.startsWith("VmRSS:")).findFirst();
                        if (line.isPresent()) peakRss.accumulateAndGet(Long.parseLong(line.get().replaceAll("[^0-9]", ""))*1024, Math::max);
                    }
                    peakLocks.accumulateAndGet(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Long.class), Math::max);
                    samples.incrementAndGet();
                } catch (Exception error) { sampleErrors.incrementAndGet(); }
            }, 0, 100, TimeUnit.MILLISECONDS);
            resourceSampler.scheduleWithFixedDelay(()->{
                try {
                    var entry=new LinkedHashMap<String,Object>();entry.put("at",Instant.now().toString());
                    if(os instanceof com.sun.management.OperatingSystemMXBean machine){entry.put("hostVisibleCpuLoad",machine.getCpuLoad());entry.put("hostVisibleMemoryTotalBytes",machine.getTotalMemorySize());entry.put("hostVisibleMemoryFreeBytes",machine.getFreeMemorySize());}
                    var stats=new ProcessBuilder("docker","stats","--no-stream","--format","{{json .}}",postgres.getContainerId(),redis.getContainerId()).redirectErrorStream(true).start();
                    try {
                        if(!stats.waitFor(4,TimeUnit.SECONDS)){stats.destroyForcibly();throw new IllegalStateException("Resource sample timed out");}
                        if(stats.exitValue()!=0)throw new IllegalStateException("Container statistics unavailable");
                        var rows=new ArrayList<Object>();for(var line:new String(stats.getInputStream().readNBytes(16000),StandardCharsets.UTF_8).lines().toList())if(!line.isBlank())rows.add(new ObjectMapper().readTree(line));
                        if(rows.size()!=2)throw new IllegalStateException("Incomplete container sample");entry.put("containers",rows);resourceSamples.add(entry);
                    } finally {if(stats.isAlive())stats.destroyForcibly();}
                }catch(Exception error){sampleErrors.incrementAndGet();}
            },0,2,TimeUnit.SECONDS);
            var ready = new CountDownLatch(CLIENTS); var fire = new CountDownLatch(1);
            var workloadDeadline=new AtomicLong();
            try (var workers = Executors.newFixedThreadPool(CLIENTS)) {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < CLIENTS; i++) {
                    final int index = i;
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        try {
                            if (!fire.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timeout");
                            for (int n = 0; System.nanoTime()<workloadDeadline.get(); n++) transfer(clients.get(index), clients.get((index+1)%CLIENTS), index, n);
                        } catch (Exception error) { throw new CompletionException(error); }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();workloadDeadline.set(System.nanoTime()+TimeUnit.SECONDS.toNanos(DURATION_SECONDS));fire.countDown();
                for (var future : futures) future.get(DURATION_SECONDS+45, TimeUnit.SECONDS);
            }
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE title=?", Integer.class, schedulerTitle)==0 && System.nanoTime()<deadline)
                Thread.sleep(50);
            var completed = jdbc.queryForObject("SELECT completed_at FROM bank_transactions WHERE title=?", java.sql.Timestamp.class, schedulerTitle);
            metrics.put("schedulerLatenessMillis", Math.max(0, Duration.between(due, completed.toInstant()).toMillis()));
            long deadlocks = jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()", Long.class)-beforeDeadlocks;
            metrics.put("databaseDeadlocks", deadlocks);
            assertThat(deadlocks).isZero();
            assertThat(operations.size()).isGreaterThanOrEqualTo(CLIENTS);
            assertThat(operations).allSatisfy(op -> assertThat(op.get("success")).isEqualTo(true));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE type='TRANSFER'", Integer.class)).isEqualTo(operations.size()+1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT idempotency_key FROM bank_transactions GROUP BY idempotency_key HAVING count(*)>1) t", Integer.class)).isZero();
            for (int i=0; i<CLIENTS; i++) {
                final int index=i,previous=(i+CLIENTS-1)%CLIENTS;
                long sent=operations.stream().filter(op->((Number)op.get("client")).intValue()==index).count();
                long received=operations.stream().filter(op->((Number)op.get("client")).intValue()==previous).count();
                var expected=BigDecimal.valueOf(INITIAL_BALANCE+received-sent+(i==0?-1:i==1?1:0));
                assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?", BigDecimal.class, clients.get(i).account())).isEqualByComparingTo(expected);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE balance<0", Integer.class)).isZero();
            // Test NOWAIT against precisely the fixture accounts; this transaction releases its locks on return.
            new TransactionTemplate(transactions).execute(status -> {
                for (var c : clients) jdbc.queryForObject("SELECT id FROM accounts WHERE id=? FOR UPDATE NOWAIT", Long.class, c.account());
                return null;
            });
            report.put("integrityPassed", true);
            resourceSampler.shutdown();assertThat(resourceSampler.awaitTermination(8,TimeUnit.SECONDS)).isTrue();
            var times=operations.stream().mapToLong(op->((Number)op.get("durationMillis")).longValue()).sorted().toArray();
            assertThat(resourceSamples).isNotEmpty();assertThat(sampleErrors.get()).isZero();
            assertThat(percentile(times,.95)).describedAs("DEV/TEST p95").isLessThanOrEqualTo((long)P95_LIMIT_MS);
            assertThat(percentile(times,.99)).describedAs("DEV/TEST p99").isLessThanOrEqualTo((long)P99_LIMIT_MS);
            report.put("verdict", "DEV_TEST_ACCEPTANCE_PASS");
        } finally {
            sampler.shutdownNow(); sampler.awaitTermination(5, TimeUnit.SECONDS);
            resourceSampler.shutdownNow();resourceSampler.awaitTermination(5,TimeUnit.SECONDS);
            var latencies = operations.stream().mapToLong(op -> ((Number)op.get("durationMillis")).longValue()).sorted().toArray();
            metrics.put("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            metrics.put("operationP95Millis", percentile(latencies,.95)); metrics.put("operationP99Millis", percentile(latencies,.99));
            metrics.put("errors", operations.stream().filter(op -> !Boolean.TRUE.equals(op.get("success"))).count());
            metrics.put("httpTimeouts", operations.stream().filter(op -> Boolean.TRUE.equals(op.get("timeout"))).count());
            metrics.put("peakHikariActive", peakActive.get()); metrics.put("peakHikariWaiting", peakWaiting.get());
            metrics.put("peakDatabaseLockWaiters", peakLocks.get()); metrics.put("peakJvmHeapBytes", peakHeap.get());
            metrics.put("peakJvmRssBytes", peakRss.get()==0 ? null : peakRss.get()); metrics.put("samples", samples.get()); metrics.put("sampleErrors", sampleErrors.get());
            metrics.put("jvmCpuTimeMillis", cpuStart<0 ? null : (((com.sun.management.OperatingSystemMXBean)os).getProcessCpuTime()-cpuStart)/1_000_000);
            report.putIfAbsent("verdict", "FAILED_OR_INCOMPLETE");
            Files.createDirectories(REPORT.getParent());
            var json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
            Files.writeString(REPORT,json);
            System.out.println("CC_TRANSFER_LOAD_REPORT "+REPORT.toAbsolutePath()+"\n"+json);
        }
    }
    static Long percentile(long[] values, double fraction) { return values.length==0 ? null : values[(int)Math.ceil(values.length*fraction)-1]; }
    Client seed() throws Exception {
        var email=UUID.randomUUID()+"@example.invalid";
        var owner=jdbc.queryForObject("INSERT INTO users(email,password_hash,first_name,last_name,role,status,created_at,updated_at,password_changed_at,email_verified) VALUES (?,?,'Load','Client','USER','ACTIVE',now(),now(),now(),true) RETURNING id",
                Long.class,email,new BCryptPasswordEncoder(4).encode(PASSWORD));
        var number=numbers.generateUnique();
        var account=jdbc.queryForObject("INSERT INTO accounts(account_number,owner_id,balance,currency,status,created_at,updated_at) VALUES (?,?,?,'PLN','ACTIVE',now(),now()) RETURNING id",Long.class,number,owner,INITIAL_BALANCE);
        var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
        var page=http.send(HttpRequest.newBuilder(URI.create(base()+"/login")).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        var csrf=csrf(page.body());
        assertThat(post(http,"/login",Map.of("_csrf",csrf,"username",email,"password",PASSWORD)).statusCode()).isEqualTo(302);
        page=http.send(HttpRequest.newBuilder(URI.create(base()+"/transfer")).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        return new Client(owner,account,number,http,csrf(page.body()));
    }
    void transfer(Client source,Client destination,int index,int sequence) throws Exception {
        long begin=System.nanoTime(); var op=new LinkedHashMap<String,Object>();
        op.put("client",index);op.put("sequence",sequence);op.put("success",false);
        try {
            var key=UUID.randomUUID().toString();
            assertThat(post(source.http(),"/transfer/confirm",Map.of("_csrf",source.csrf(),"idempotencyKey",key,
                    "recipientName","Load Recipient","destinationAccountNumber",destination.number(),"amount","1.00","title","Synthetic concurrent load")).statusCode()).isEqualTo(200);
            var first=post(source.http(),"/transfer/execute",Map.of("_csrf",source.csrf(),"idempotencyKey",key));
            op.put("httpStatus",first.statusCode());
            var location=first.headers().firstValue("location").orElse("");
            assertThat(first.statusCode()).isEqualTo(302);assertThat(location).contains("/transfer/success/");
            var replay=post(source.http(),"/transfer/execute",Map.of("_csrf",source.csrf(),"idempotencyKey",key));
            assertThat(replay.statusCode()).isEqualTo(302);assertThat(replay.headers().firstValue("location").orElse("")).isEqualTo(location);
            op.put("success",true);
        } catch (HttpTimeoutException error) {op.put("timeout",true);throw error;}
        finally {op.put("durationMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));operations.add(op);}
    }
    String base(){return "http://127.0.0.1:"+port;}
    HttpResponse<String> post(HttpClient http,String path,Map<String,String> fields)throws Exception {
        var body=fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return http.send(HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static String csrf(String html){var m=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);if(!m.find())throw new AssertionError("Missing fixture CSRF");return m.group(1);}
}
