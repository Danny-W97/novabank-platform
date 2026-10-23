package org.demo.bankdemo.infrastructure;

import com.redis.testcontainers.RedisContainer;
import org.demo.bankdemo.security.TotpSecretCipher;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** Two independent packaged JVMs, real cookies/CSRF/HTTP, only disposable container data. */
class SharedSessionHttpIT {
    static PostgreSQLContainer postgres;
    static RedisContainer redis;
    static Process first, second;
    static int a, b;
    static JdbcTemplate jdbc;
    static String key;
    CookieManager cookies;
    HttpClient client;
    static final String PASSWORD = "SyntheticPassword9!";

    @BeforeAll static void start() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        redis = new RedisContainer(DockerImageName.parse("redis:7.4"));
        // Docker may reallocate an ephemeral published port on restart. Keep this owned fixture's endpoint stable.
        int redisPort=port();
        redis.withCreateContainerCmdModifier(command->command.getHostConfig().withPortBindings(
            new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",redisPort),new com.github.dockerjava.api.model.ExposedPort(6379))));
        try {
            postgres.start(); redis.start();
            key = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
            a = port(); b = port(); while (b == a) b = port();
            first = launch(a, "a"); waitFor(first, a);
            second = launch(b, "b"); waitFor(second, b);
            jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        } catch (Exception failure) {stop(); throw failure;}
    }
    @AfterAll static void stop() throws Exception {
        for (var process : new Process[]{second, first}) if (process != null) {
            process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
        if (redis != null) redis.stop(); if (postgres != null) postgres.stop();
    }
    @BeforeEach void browser() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }
    @Test void loginAndLogoutAreSharedAndEndpointMatrixIsEnforced() throws Exception {
        var email = seed(false, null);
        for (var path:List.of("/transfer","/scheduled-transfers","/exchange","/history","/recipients","/notifications","/profile","/admin"))
            assertThat(get(a,path).statusCode()).describedAs(path).isEqualTo(302);
        assertThat(get(a, "/dashboard").statusCode()).isEqualTo(302);
        // Materialize the server token. With no stored token, invalidSessionUrl handles MissingCsrfToken
        // using a login redirect; an omitted request token against an existing server token is a 403.
        csrf(get(a,"/login").body());
        var denied=post(a, "/login", Map.of("username", email, "password", PASSWORD));
        assertThat(denied.statusCode()).describedAs(location(denied)).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=? AND successful=true",Integer.class,email)).isZero();
        assertThat(get(b,"/dashboard").statusCode()).isEqualTo(302);
        var basic=Base64.getEncoder().encodeToString((email+":"+PASSWORD).getBytes(StandardCharsets.UTF_8));
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+a+"/dashboard"))
                .header("Authorization","Basic "+basic).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(302);
        login(a, email, "/dashboard");
        assertThat(get(b, "/dashboard").statusCode()).isEqualTo(200);
        assertThat(get(b, "/admin").statusCode()).isEqualTo(403);
        assertThat(get(b, "/api/not-a-real-endpoint").statusCode()).isEqualTo(403);
        assertThat(get(b,"/api/payment-authorizations/missing/status").statusCode()).isEqualTo(401);
        var token=csrf(get(b,"/login").body());
        for (var path:List.of("/login/2fa","/transfer/execute","/exchange/execute","/recipients","/profile/password"))
            assertThat(post(b,path,Map.of()).statusCode()).describedAs(path).isEqualTo(403);
        assertThat(post(b, "/transfer/execute", Map.of("idempotencyKey", UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
        assertThat(get(b,"/dashboard").statusCode()).isEqualTo(200);
        assertThat(post(a, "/logout", Map.of("_csrf", token)).statusCode()).isEqualTo(302);
        assertThat(get(b, "/dashboard").statusCode()).isEqualTo(302);
    }
    @Test void k4AdminBlockingRevokesExistingClientSessionAcrossBothProcesses()throws Exception{
        var email=seed(false,null);login(a,email,"/dashboard");
        String clientCookie=cookies.getCookieStore().getCookies().stream().map(cookie->cookie.getName()+"="+cookie.getValue()).collect(java.util.stream.Collectors.joining("; "));
        var account=jdbc.queryForObject("SELECT a.id FROM accounts a JOIN users u ON u.id=a.owner_id WHERE u.email=?",Long.class,email);
        var admin=seed(false,null);jdbc.update("UPDATE users SET role='ADMIN' WHERE email=?",admin);
        browser();login(b,admin,"/dashboard");var token=csrf(get(b,"/admin").body());
        assertThat(post(b,"/admin/accounts/"+account+"/block",Map.of("_csrf",token)).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email=?",String.class,email)).isEqualTo("BLOCKED");
        for(int endpoint:List.of(a,b)){
            var result=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+endpoint+"/dashboard")).header("Cookie",clientCookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(302);assertThat(location(result)).contains("/login");
        }
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",java.math.BigDecimal.class,account)).isEqualByComparingTo("1000");
    }
    @Test void packagedAdministrativeToolDoesNotRunFlywayOrStartAnotherApplication() throws Exception {
        var before=jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class);
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx128m", "-Dloader.main=org.demo.bankdemo.maintenance.MaintenanceCommand", "-cp",
                Path.of("target/Bank-demo-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "--profile=test", "--operation=payment-review");
        var env=command.environment(); env.clear();
        env.put("TEST_POSTGRES_URL",postgres.getJdbcUrl().split("\\?",2)[0]);
        env.put("TEST_POSTGRES_USER",postgres.getUsername()); env.put("TEST_POSTGRES_PASSWORD",postgres.getPassword());
        var process=command.redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(25,TimeUnit.SECONDS)).isTrue();
            var output=new String(process.getInputStream().readNBytes(64000),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).describedAs(output).isZero();
            assertThat(output).contains("INVENTORY_ONLY").doesNotContain("Started BankDemoApplication");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class)).isEqualTo(before);
        } finally {if(process.isAlive()) process.destroyForcibly().waitFor();}
    }
    @Test void killedInstanceDoesNotLoseSharedSessionOrDraftAndCanRejoin() throws Exception {
        var email=seed(false,null);login(a,email,"/dashboard");
        var token=csrf(get(a,"/transfer").body());
        var destination=jdbc.queryForObject("SELECT account_number FROM accounts WHERE owner_id=(SELECT id FROM users WHERE email=?)",String.class,seed(false,null));
        jdbc.update("INSERT INTO saved_recipients(owner_id,alias,recipient_name,account_number,created_at,updated_at) SELECT id,'Failover recipient','Synthetic Recipient',?,now(),now() FROM users WHERE email=?",destination,email);
        var id=UUID.randomUUID().toString();
        assertThat(draft(a,token,id,destination,"Synthetic failover draft").statusCode()).isEqualTo(200);
        try {
            first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(get(b,"/dashboard").statusCode()).isEqualTo(200);
            var result=post(b,"/transfer/execute",Map.of("_csrf",token,"idempotencyKey",id));
            assertThat(location(result)).contains("/transfer/success/");
            first=launch(a,"a-restarted");waitFor(first,a);
            var replay=post(a,"/transfer/execute",Map.of("_csrf",token,"idempotencyKey",id));
            assertThat(URI.create(location(replay)).getPath()).isEqualTo(URI.create(location(result)).getPath());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,id)).isEqualTo(1);
        } finally {if(!first.isAlive()){first=launch(a,"a-recovered");waitFor(first,a);}}
    }
    @Test void redisOutageFailsClosedAndRecoveryKeepsOnlyExistingAuthentication() throws Exception {
        var email=seed(false,null);login(a,email,"/dashboard");
        long before=jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=? AND successful=true",Long.class,email);
        try {
            redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
            try {assertThat(get(b,"/dashboard").statusCode()).isGreaterThanOrEqualTo(500);}
            catch(java.net.http.HttpTimeoutException unavailable){/* No protected response is also fail-closed. */}
        } finally {redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();}
        assertThat(get(a,"/dashboard").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=? AND successful=true",Long.class,email)).isEqualTo(before);
        // No cookie and no new login may acquire the recovered session.
        browser();assertThat(get(b,"/dashboard").statusCode()).isEqualTo(302);
        var pending=seed(true,new dev.samstevens.totp.secret.DefaultSecretGenerator().generate());login(a,pending,"/login/2fa");
        redis.getDockerClient().restartContainerCmd(redis.getContainerId()).withTimeout(3).exec();
        int recovered=500;var deadline=Instant.now().plusSeconds(25);
        while(Instant.now().isBefore(deadline)){
            try{recovered=get(b,"/dashboard").statusCode();assertThat(recovered).isNotEqualTo(200);if(recovered==302)break;}catch(java.io.IOException ignored){}
            Thread.sleep(100);
        }
        assertThat(recovered).isEqualTo(302);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=? AND successful=true",Long.class,pending)).isZero();
    }
    @Test void processKilledAfterLedgerInsertBeforeCommitLeavesNoPartialTransfer()throws Exception{
        var email=seed(false,null);var recipient=seed(false,null);
        var destination=jdbc.queryForObject("SELECT a.account_number FROM accounts a JOIN users u ON a.owner_id=u.id WHERE u.email=?",String.class,recipient);
        jdbc.update("INSERT INTO saved_recipients(owner_id,alias,recipient_name,account_number,created_at,updated_at) SELECT id,'Crash recipient','Synthetic Recipient',?,now(),now() FROM users WHERE email=?",destination,email);
        login(a,email,"/dashboard");var token=csrf(get(a,"/transfer").body());var id=UUID.randomUUID().toString();
        assertThat(draft(a,token,id,destination,"Synthetic crash boundary").statusCode()).isEqualTo(200);
        jdbc.execute("CREATE FUNCTION cc_test_pause_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.title='Synthetic crash boundary' THEN PERFORM pg_sleep(30); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER cc_test_pause_commit AFTER INSERT ON bank_transactions FOR EACH ROW EXECUTE FUNCTION cc_test_pause_commit()");
        var worker=Executors.newSingleThreadExecutor();
        try {
            var response=worker.submit(()->post(a,"/transfer/execute",Map.of("_csrf",token,"idempotencyKey",id)));
            var deadline=Instant.now().plusSeconds(12);boolean paused=false;
            while(Instant.now().isBefore(deadline)){
                paused=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep')",Boolean.class));
                if(paused)break;Thread.sleep(50);
            }
            assertThat(paused).describedAs("Real transaction reached controlled pre-commit boundary").isTrue();
            first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
            // Stop only the backend sleeping in our fixture trigger; no unrelated session or DEV endpoint is selected.
            jdbc.queryForList("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep'");
            try{response.get(25,TimeUnit.SECONDS);}catch(ExecutionException expected){assertThat(expected.getCause()).isInstanceOf(java.io.IOException.class);}
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT a.balance FROM accounts a JOIN users u ON u.id=a.owner_id WHERE u.email=?",java.math.BigDecimal.class,email)).isEqualByComparingTo("1000");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS cc_test_pause_commit ON bank_transactions");jdbc.execute("DROP FUNCTION IF EXISTS cc_test_pause_commit()");
            worker.shutdownNow();if(!first.isAlive()){first=launch(a,"a-after-crash");waitFor(first,a);}
        }
        var retry=post(b,"/transfer/execute",Map.of("_csrf",token,"idempotencyKey",id));assertThat(location(retry)).contains("/transfer/success/");
        first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
        try{assertThat(location(post(b,"/transfer/execute",Map.of("_csrf",token,"idempotencyKey",id)))).isEqualTo(location(retry));}
        finally{first=launch(a,"a-after-postcommit-crash");waitFor(first,a);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void challengeFailuresAcrossProcessesShareOneLimitAndValidCodeCompletesOnce() throws Exception {
        var secret = new dev.samstevens.totp.secret.DefaultSecretGenerator().generate();
        var email = seed(true, secret);
        login(a, email, "/login/2fa");
        assertThat(get(b, "/dashboard").statusCode()).isEqualTo(302);
        var failedToken = csrf(get(b, "/login/2fa").body());
        try (var executor = Executors.newFixedThreadPool(5)) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int n = 0; n < 5; n++) {
                int endpoint = n % 2 == 0 ? a : b;
                futures.add(executor.submit(() -> post(endpoint, "/login/2fa", Map.of("_csrf", failedToken, "code", "invalid"))));
            }
            for (var future : futures) assertThat(future.get(25, TimeUnit.SECONDS).statusCode()).isEqualTo(302);
        }
        assertThat(location(get(a, "/login/2fa"))).endsWith("/login?error");
        login(b, email, "/login/2fa");
        var token = csrf(get(a, "/login/2fa").body());
        var code = new dev.samstevens.totp.code.DefaultCodeGenerator(dev.samstevens.totp.code.HashingAlgorithm.SHA1, 6)
                .generate(secret, Instant.now().getEpochSecond() / 30);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var left=executor.submit(()->post(a,"/login/2fa",Map.of("_csrf",token,"code",code)));
            var right=executor.submit(()->post(b,"/login/2fa",Map.of("_csrf",token,"code",code)));
            var responses=List.of(left.get(25,TimeUnit.SECONDS),right.get(25,TimeUnit.SECONDS));
            assertThat(responses).allSatisfy(response->assertThat(response.statusCode()).isEqualTo(302));
            assertThat(responses.stream().filter(response->location(response).contains("/dashboard")).count()).isEqualTo(1);
        }
        assertThat(get(a, "/dashboard").statusCode()).isEqualTo(200);
        // Replaying the consumed challenge never creates another successful login.
        post(a, "/login/2fa", Map.of("_csrf", token, "code", code));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=? AND successful=true", Integer.class, email)).isEqualTo(1);
    }

    @Test void accountBlockedAfterPasswordCannotCompleteTwoFactorInAnotherProcess() throws Exception {
        var secret=new dev.samstevens.totp.secret.DefaultSecretGenerator().generate();
        var email=seed(true,secret);login(a,email,"/login/2fa");
        var token=csrf(get(b,"/login/2fa").body());
        jdbc.update("UPDATE users SET status='BLOCKED' WHERE email=?",email);
        var code=new dev.samstevens.totp.code.DefaultCodeGenerator(dev.samstevens.totp.code.HashingAlgorithm.SHA1,6)
                .generate(secret,Instant.now().getEpochSecond()/30);
        var result=post(b,"/login/2fa",Map.of("_csrf",token,"code",code));
        assertThat(result.statusCode()).isEqualTo(302);assertThat(location(result)).endsWith("/login?error");
        assertThat(get(a,"/dashboard").statusCode()).isEqualTo(302);
        assertThat(get(b,"/dashboard").statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=? AND successful=true",Integer.class,email)).isZero();
    }

    @Test void lostHttpReplyAfterTransferCommitCanReplayAcrossProcessesWithoutAnotherDebit() throws Exception {
        var email=seed(false,null);var recipient=seed(false,null);
        var destination=jdbc.queryForObject("SELECT a.account_number FROM accounts a JOIN users u ON a.owner_id=u.id WHERE u.email=?",String.class,recipient);
        jdbc.update("INSERT INTO saved_recipients(owner_id,alias,recipient_name,account_number,created_at,updated_at) SELECT id,'Synthetic destination','Synthetic Recipient',?,now(),now() FROM users WHERE email=?",destination,email);
        login(a,email,"/dashboard");var token=csrf(get(a,"/transfer").body());
        var key=UUID.randomUUID().toString();
        assertThat(draft(a,token,key,destination,"Lost HTTP reply").statusCode()).isEqualTo(200);
        var committed=new CountDownLatch(1);var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var proxy=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.createContext("/transfer/execute",exchange->{
            try {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+a+"/transfer/execute"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type","application/x-www-form-urlencoded");
                var cookie=exchange.getRequestHeaders().getFirst("Cookie");if(cookie!=null)request.header("Cookie",cookie);
                var response=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                        request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()==302 && location(response).contains("/transfer/success/"))committed.countDown();
                // Drop the reply AFTER the real bank response; do not fabricate a business refusal.
                interrupted.set(true);
            } catch(Exception failure){ /* Only the synthetic transport is interrupted. */ }
            finally {exchange.close();}
        });
        proxy.start();
        try {
            var body="_csrf="+encode(token)+"&idempotencyKey="+encode(key);
            try {
                client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/transfer/execute"))
                        .timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding());
                fail("The transport must not return a successful payment response");
            } catch(java.io.IOException expected) { }
            assertThat(interrupted.get()).isTrue();assertThat(committed.await(5,TimeUnit.SECONDS)).isTrue();
            var retry=post(b,"/transfer/execute",Map.of("_csrf",token,"idempotencyKey",key));
            assertThat(location(retry)).contains("/transfer/success/");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT a.balance FROM accounts a JOIN users u ON a.owner_id=u.id WHERE u.email=?",java.math.BigDecimal.class,email)).isEqualByComparingTo("999.00");
            assertThat(jdbc.queryForObject("SELECT a.balance FROM accounts a JOIN users u ON a.owner_id=u.id WHERE u.email=?",java.math.BigDecimal.class,recipient)).isEqualByComparingTo("1001.00");
        } finally {proxy.stop(0);}
    }
    @Test void parallelDraftsSurviveAcrossProcessesWithoutOverwritingEachOther() throws Exception {
        var email = seed(false, null);
        var recipient = seed(false, null);
        var account = jdbc.queryForObject("SELECT a.account_number FROM accounts a JOIN users u ON a.owner_id=u.id WHERE u.email=?", String.class, recipient);
        login(a, email, "/dashboard");
        var token = csrf(get(a, "/transfer").body());
        var one = UUID.randomUUID().toString(); var two = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> draft(a, token, one, account, "Draft Alpha"));
            var right = executor.submit(() -> draft(b, token, two, account, "Draft Beta"));
            assertThat(left.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(right.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        for (var entry : Map.of(one, "Draft Alpha", two, "Draft Beta").entrySet()) {
            assertThat(post(b, "/transfer/edit", Map.of("_csrf", token, "idempotencyKey", entry.getKey())).statusCode()).isEqualTo(302);
            assertThat(get(a, "/transfer").body()).contains(entry.getValue()).contains(entry.getKey());
        }
    }
    @Test void sanitizingProxyDiscardsForgedHeadersButDirectBackendStillNeedsNetworkIsolation() throws Exception {
        var forged="for=192.0.2.77;proto=https;host=attacker.example.invalid";
        var direct=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+a+"/dashboard"))
                .header("Forwarded",forged).GET().build(),HttpResponse.BodyHandlers.ofString());
        // This documents the framework trust boundary, not a claim that the real firewall is safe.
        assertThat(location(direct)).contains("attacker.example.invalid");
        var proxy=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.createContext("/",exchange->{
            try {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+a+exchange.getRequestURI()))
                        .header("Forwarded","for=127.0.0.1;proto=https;host=gateway.example.invalid")
                        .method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                for(var header:List.of("Cookie","Content-Type"))
                    if(exchange.getRequestHeaders().getFirst(header)!=null) request.header(header,exchange.getRequestHeaders().getFirst(header));
                var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
                for(var header:List.of("location","set-cookie")) {
                    var values=response.headers().allValues(header);
                    if(!values.isEmpty()) exchange.getResponseHeaders().put(header,values);
                }
                exchange.sendResponseHeaders(response.statusCode(),response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch(Exception failure) {exchange.sendResponseHeaders(502,-1);} finally {exchange.close();}
        });
        proxy.start();
        try {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/dashboard"))
                    .header("Forwarded",forged).header("X-Forwarded-Host","attacker.example.invalid")
                    .header("X-Forwarded-Proto","http").header("X-Forwarded-For","192.0.2.77").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(location(response)).contains("gateway.example.invalid").doesNotContain("attacker.example.invalid");
        } finally {proxy.stop(0);}
    }
    HttpResponse<String> draft(int endpoint, String token, String id, String account, String title) throws Exception {
        return post(endpoint, "/transfer/confirm", Map.of("_csrf", token, "idempotencyKey", id,
                "recipientName", "Synthetic Recipient", "destinationAccountNumber", account, "amount", "1.00", "title", title));
    }
    void login(int endpoint, String email, String target) throws Exception {
        var token = csrf(get(endpoint, "/login").body());
        var response = post(endpoint, "/login", Map.of("_csrf", token, "username", email, "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).contains(target);
    }
    String seed(boolean enabled, String secret) {
        var email = UUID.randomUUID()+"@example.invalid";
        var encrypted = enabled ? new TotpSecretCipher(key).encrypt(secret) : null;
        var id = jdbc.queryForObject("INSERT INTO users(email,password_hash,first_name,last_name,role,status,created_at,updated_at,password_changed_at,email_verified,totp_enabled,totp_secret_encrypted) VALUES (?,?,'Synthetic','Client','USER','ACTIVE',now(),now(),now(),true,?,?) RETURNING id",
                Long.class, email, new BCryptPasswordEncoder(4).encode(PASSWORD), enabled, encrypted);
        var bban = String.format("%024d", id);
        var checksum = 98 - new java.math.BigInteger(bban+"252100").mod(java.math.BigInteger.valueOf(97)).intValue();
        var number = String.format("%02d", checksum)+bban;
        jdbc.update("INSERT INTO accounts(account_number,owner_id,balance,currency,status,created_at,updated_at) VALUES (?,?,1000,'PLN','ACTIVE',now(),now())", number, id);
        return email;
    }
    HttpResponse<String> get(int endpoint, String path) throws Exception {return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+endpoint+path)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> post(int endpoint, String path, Map<String,String> values) throws Exception {
        var body = values.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+endpoint+path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String encode(String value) {return URLEncoder.encode(value, StandardCharsets.UTF_8);}
    static String csrf(String html) {
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        if (!match.find()) throw new AssertionError("Missing CSRF token in fixture page.");
        return match.group(1);
    }
    static String location(HttpResponse<?> response) {return response.headers().firstValue("location").orElse("");}
    static int port() throws Exception {try (var socket = new java.net.ServerSocket(0)) {return socket.getLocalPort();}}

    @Test void restoredBackupRotatesKeysAndAuthenticatesWithOriginalTotpSeed() throws Exception {
        var secret = new dev.samstevens.totp.secret.DefaultSecretGenerator().generate();
        var email = seed(true, secret);
        var sourceCiphertext = jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE email=?", String.class, email);
        var backup = Path.of("target", "synthetic-totp-restore-"+UUID.randomUUID()+".dump").toAbsolutePath();
        var restored = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        var recoveryRedis = new RedisContainer(DockerImageName.parse("redis:7.4"));
        Process application = null;
        try {
            // The source contains only test-generated identities and encrypted test seeds.
            var dump = postgres.execInContainer("pg_dump", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                    "-Fc", "--no-owner", "--no-privileges", "-f", "/tmp/totp-recovery.dump");
            assertThat(dump.getExitCode()).isZero();
            postgres.copyFileFromContainer("/tmp/totp-recovery.dump", backup.toString());
            var shaBefore = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup));
            restored.start(); recoveryRedis.start();
            restored.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(backup), "/tmp/totp-recovery.dump");
            assertThat(restored.execInContainer("pg_restore", "-U", restored.getUsername(), "-d", restored.getDatabaseName(),
                    "--exit-on-error", "--no-owner", "--no-privileges", "/tmp/totp-recovery.dump").getExitCode()).isZero();
            var source = new DriverManagerDataSource(restored.getJdbcUrl(),restored.getUsername(),restored.getPassword());
            var recovered = new JdbcTemplate(source);
            assertThat(recovered.queryForObject("SELECT totp_secret_encrypted FROM users WHERE email=?",String.class,email)).isEqualTo(sourceCiphertext);
            var nextKey = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
            var cipher = new TotpSecretCipher(key,"recovery="+nextKey,"recovery");
            var rotation = new org.demo.bankdemo.maintenance.TotpRotationAdministration(source,cipher);
            long after = 0;
            while (true) {
                var plan = rotation.plan(after,100);
                if (plan.inspected()==0) break;
                var applied = rotation.apply(after,100,plan.planDigest(),"ROTATE");
                assertThat(rotation.apply(after,100,plan.planDigest(),"ROTATE").replayed()).isTrue();
                after=applied.nextCheckpoint();
            }
            assertThat(cipher.decrypt(recovered.queryForObject("SELECT totp_secret_encrypted FROM users WHERE email=?",String.class,email))).isEqualTo(secret);
            var endpoint = port();
            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-XX:ActiveProcessorCount=2",
                    "-jar",Path.of("target/Bank-demo-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"--spring.profiles.active=test",
                    "--server.address=127.0.0.1","--server.port="+endpoint,"--management.server.port=-1",
                    "--app.transfer.scheduler-delay-ms=3600000","--app.exchange.quote-cleanup-delay-ms=3600000","--app.retention.enabled=false");
            var env=command.environment();env.clear();env.put("PATH",System.getenv().getOrDefault("PATH",""));
            env.put("TEST_POSTGRES_URL",restored.getJdbcUrl());env.put("TEST_POSTGRES_USER",restored.getUsername());env.put("TEST_POSTGRES_PASSWORD",restored.getPassword());
            env.put("TEST_REDIS_HOST",recoveryRedis.getHost());env.put("TEST_REDIS_PORT",recoveryRedis.getFirstMappedPort().toString());
            env.put("TEST_TOTP_ENCRYPTION_KEY",key);env.put("TEST_TOTP_ENCRYPTION_KEYS","recovery="+nextKey);env.put("TEST_TOTP_ENCRYPTION_ACTIVE_ID","recovery");
            application=command.redirectErrorStream(true).redirectOutput(Path.of("target/synthetic-totp-recovery-http.log").toFile()).start();
            waitFor(application,endpoint);browser();
            login(endpoint,email,"/login/2fa");
            assertThat(get(endpoint,"/dashboard").statusCode()).isEqualTo(302);
            var token=csrf(get(endpoint,"/login/2fa").body());
            var code=new dev.samstevens.totp.code.DefaultCodeGenerator(dev.samstevens.totp.code.HashingAlgorithm.SHA1,6)
                    .generate(secret,Instant.now().getEpochSecond()/30);
            assertThat(location(post(endpoint,"/login/2fa",Map.of("_csrf",token,"code",code)))).contains("/dashboard");
            assertThat(get(endpoint,"/dashboard").statusCode()).isEqualTo(200);
            assertThat(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup))).isEqualTo(shaBefore);
            assertThat(jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE email=?",String.class,email)).isEqualTo(sourceCiphertext);
            System.out.println("CC_TOTP_RECOVERY_LOGIN_PASS: synthetic pg_dump restored, rotation replay safe, original token accepted, dashboard HTTP 200. Operator backups/keys were not used.");
        } finally {
            if(application!=null){application.destroy();if(!application.waitFor(10,TimeUnit.SECONDS))application.destroyForcibly().waitFor();}
            recoveryRedis.stop();restored.stop();Files.deleteIfExists(backup);
        }
    }
    @Test void merchantCancelEndpointRequiresApiKeyButNotBrowserCsrf()throws Exception{
        var uri=URI.create("http://127.0.0.1:"+a+"/api/payment-authorizations/by-key/synthetic-missing/cancel");
        for(var key:List.of("","invalid-synthetic-key","test-api-key")){
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody());
            if(!key.isEmpty())builder.header("X-API-Key",key);
            int code=client.send(builder.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
            assertThat(code).isEqualTo(key.equals("test-api-key")?404:401);
        }
    }
    static Process launch(int endpoint, String name) throws Exception {
        var jar = Path.of("target/Bank-demo-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m", "-XX:ActiveProcessorCount=2",
                "-jar", jar.toString(), "--spring.profiles.active=test", "--server.port="+endpoint,
                "--server.address=127.0.0.1", "--management.server.port=-1", "--spring.data.redis.timeout=2s", "--spring.data.redis.connect-timeout=1s", "--app.transfer.scheduler-delay-ms=3600000",
                "--app.exchange.quote-cleanup-delay-ms=3600000", "--app.retention.enabled=false", "--spring.main.banner-mode=off");
        var env = command.environment(); env.clear();
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("TEST_POSTGRES_URL", postgres.getJdbcUrl()); env.put("TEST_POSTGRES_USER", postgres.getUsername()); env.put("TEST_POSTGRES_PASSWORD", postgres.getPassword());
        env.put("TEST_REDIS_HOST", redis.getHost()); env.put("TEST_REDIS_PORT", redis.getFirstMappedPort().toString()); env.put("TEST_TOTP_ENCRYPTION_KEY", key);
        return command.redirectErrorStream(true).redirectOutput(Path.of("target/shared-session-"+name+".log").toFile()).start();
    }
    static void waitFor(Process process, int endpoint) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var deadline = Instant.now().plusSeconds(100);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) throw new AssertionError("Fixture JVM stopped; see target/shared-session logs.");
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+endpoint+"/login")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(200);
        }
        throw new AssertionError("Fixture JVM startup timeout; see target/shared-session logs.");
    }
}
