# Software Test Types

[English](Software_Test_Types.md) | [Polski](Software_Test_Types.pl.md)

Testing terminology is easy to mix up. Unit, integration and E2E primarily describe the scope of the system under test, while smoke, regression, sanity and performance describe the purpose or the way a test suite is assembled. An E2E test can therefore be both a smoke test and a regression test.

ISTQB also distinguishes test levels such as component, integration, system and acceptance from other test classifications.

## 1. The overall map of test types

The key distinction is that the following are not one linear sequence:

```text
TEST LEVEL:
Unit -> Component -> Integration -> System -> E2E

PURPOSE / SUITE:
Smoke
Regression
Sanity
Performance
Security
...
```

Smoke is not a level between integration and E2E. It is a focused set of checks that can be applied at different levels.

## 2. Unit tests

A unit test checks a small piece of program logic in isolation: usually one class, method, function or small component. In a typical Java/Spring Boot application, a unit test should not require the entire Spring context, a database, Redis, HTTP or an external API. Dependencies can be replaced with test doubles such as mocks or stubs.

Example:

```java
class TransferServiceTest {
    @Test
    void shouldRejectTransferWhenBalanceIsTooLow() {
        Account account = new Account(new BigDecimal("100.00"));
        assertThrows(
            InsufficientFundsException.class,
            () -> account.transfer(new BigDecimal("150.00"))
        );
    }
}
```

The test focuses on a balance of 100, a transfer of 150 and the expected `InsufficientFundsException`. It does not yet test HTTP, Spring MVC, PostgreSQL, Redis, authorization, the frontend or the network.

### Advantages

- very fast;
- easy to run;
- the source of a failure is usually easy to identify;
- suitable for every commit;
- good for edge cases;
- requires little infrastructure.

### Limitations

Each element can work in isolation while the collaboration between them is broken:

```text
AccountService ✓
TransferService ✓
Repository     ✓
Notification   ✓

After integration:
AccountService -> TransferService -> Repository ✗
```

This is where integration tests become necessary.

## 3. Component and slice tests

This is an intermediate level, especially common in Spring Boot. It tests a larger part of the application than a single class without starting the entire system.

For example:

```java
@WebMvcTest(TransferController.class)
class TransferControllerTest {
}
```

Spring Boot can start the MVC infrastructure needed by the controller while replacing other dependencies with mocks. `MockMvc` allows controllers to be tested without starting a full HTTP server.

A slice test may verify:

```text
POST /api/transfers
        |
    JSON request
        |
TransferController
        |
HTTP 201 Created
```

It does not yet verify the complete Controller -> Service -> Repository -> PostgreSQL path. Common Spring slices cover controllers, repositories, JSON serialization, security, JPA and WebFlux. Slice tests usually provide more confidence than unit tests while remaining faster than full integration tests.

## 4. Integration tests

An integration test answers the question:

> Do several parts of the system work correctly together?

For example:

```text
TransferService
      |
TransferRepository
      |
Hibernate
      |
PostgreSQL
```

The database can be real and started in Docker through Testcontainers:

```java
@SpringBootTest
@Testcontainers
class TransferIntegrationTest {
    @Test
    void shouldPersistTransfer() {
        // ...
    }
}
```

The test no longer verifies only `transfer.calculate()`. It also verifies service, repository, Hibernate, SQL and PostgreSQL working together.

## 5. Unit versus integration tests

The difference is especially clear with a repository:

```text
Unit test:
TransferService -> Mock TransferRepository

Integration test:
TransferService -> TransferRepository -> Hibernate -> PostgreSQL
```

The unit test uses a fake repository. The integration test uses the real repository and database. It is therefore possible to have:

```text
Unit test:        PASS
Integration test: FAIL
```

For example, a Java property named `transferDate` may be mapped to an SQL column named `transfer_date` incorrectly. A unit test with a mocked repository will not detect that mapping problem.

## 6. API tests

An API test checks the public application interface, for example:

```http
POST /api/transfers
```

The test can send:

```json
{
  "fromAccount": "123",
  "toAccount": "456",
  "amount": 500
}
```

and expect `HTTP 201 Created` together with the correct JSON response.

An API test may be a component, integration, system or API-level E2E test. The classification depends on how much real application infrastructure is started. A mocked controller is closer to a component test; a controller, service and database together form an integration test.

Spring Boot can also run tests with a real server, for example with `@SpringBootTest(webEnvironment = RANDOM_PORT)`.

## 7. Contract tests

A contract test verifies that two services agree on how they communicate.

Suppose `BankService` expects `EmailService` to receive:

```json
{
  "email": "test@example.com",
  "message": "Transfer completed"
}
```

If `EmailService` changes the contract to:

```json
{
  "recipient": "test@example.com",
  "content": "Transfer completed"
}
```

both applications may still pass their own tests, while the integration between them fails. Contract testing detects this type of incompatibility.

It is particularly useful for microservices, external APIs, Kafka, REST, GraphQL and distributed systems.

## 8. System tests

A system test checks a completely integrated system as a whole:

```text
Spring Boot + PostgreSQL + Redis + Security + REST API
```

The frontend does not have to be involved. The complete backend can be tested through HTTP in an environment close to the real deployment.

## 9. End-to-end tests

An E2E test checks a complete scenario from the perspective of a user or external client:

```text
Login -> choose an account -> make a transfer ->
persist the transfer -> update the balance -> show confirmation
```

An E2E test may cover:

```text
Browser -> Frontend -> HTTP -> Backend -> Security ->
Business logic -> Database
```

Selenium and Playwright are examples of browser automation tools. Browser tests are relatively expensive, so lower-level tests should cover as much logic as possible.

## 10. Acceptance tests / UAT

Acceptance testing answers a business question:

> Does the system meet the requirements and is it ready to be accepted by the user or business?

For example, if a customer with PLN 1,000 transfers PLN 500, the resulting balance should be PLN 500. The acceptance test focuses on that business outcome, not on whether the implementation uses Hibernate, JPA, Redis, Kafka or REST.

## 11. Smoke tests

A smoke test asks:

> Do the most important functions work well enough to continue testing?

A typical smoke suite may verify that:

- the application starts;
- login works;
- the database responds;
- `GET /accounts` works;
- a transfer can be created;
- logout works.

It does not cover every possible variant. Smoke tests are useful as a quality gate immediately after deployment.

## 12. Sanity tests

Sanity testing is similar to smoke testing but usually narrower. If a commission calculation was changed, a sanity test focuses on the transfer, commission and balance instead of the entire application.

```text
Smoke:  Is the application generally alive?
Sanity: Does the changed area basically work?
```

Organizations use the terms smoke and sanity differently, so the defined contents of the suite matter more than the label.

## 13. Regression tests

Regression testing checks whether a new change broke something that previously worked. A new international-transfer feature may pass its own tests but break domestic transfers because of a change in `TransferService`.

Regression is not a separate test level. A regression suite can contain unit, integration, API and E2E tests:

```text
Regression suite = Unit + Integration + API + E2E
```

## 14. Re-test / confirmation test

A confirmation test checks whether a specific bug was fixed. If `BUG-123` caused a PLN 100 transfer to charge PLN 200, the confirmation test verifies that this exact defect is gone.

Regression testing additionally checks whether fixing `BUG-123` broke other transfer scenarios:

```text
Re-test   -> Does the fix work?
Regression -> Did the fix break something else?
```

## 15. Performance tests

Performance testing asks how the system behaves under a defined load rather than only whether it returns the correct result. Typical measurements include response time, throughput, CPU, memory, database connections and error rate.

### Load test

A load test uses expected traffic, for example 100, 500 or 1,000 users, and verifies whether the system meets its performance targets.

## 16. Stress tests

A stress test deliberately exceeds normal limits:

```text
100 users       PASS
500 users       PASS
1,000 users     PASS
5,000 users     PASS
10,000 users    FAIL
```

The goal is to find where the system fails and how it behaves after overload.

## 17. Spike tests

A spike test simulates a sudden traffic increase, such as normal traffic followed by a promotion that produces twenty times more users. It checks how quickly and safely the system handles the sudden change.

## 18. Soak / endurance tests

An endurance test runs the system for a long period, such as 1, 6, 24 or 72 hours. It looks for memory leaks, connection-pool exhaustion, growing queues, cache problems and gradual performance degradation.

## 19. Security tests

Security tests check authentication, authorization, session management, input validation, SQL injection, XSS, CSRF, IDOR, rate limiting and data protection.

For example, if account 456 belongs to user B, user A requesting `GET /accounts/456` should receive `403`, not `200`. Business logic can be correct while access control remains unsafe.

## 20. The test pyramid

A practical rule is to have many inexpensive lower-level tests and fewer expensive E2E tests:

```text
             E2E
        System tests
    Integration / Component
          Unit tests
```

The exact ratio is not universal. The useful principle is:

> Test a defect at the lowest level that can detect it reliably.

If `amount > balance` can be verified by a unit test in a few milliseconds, there is no reason to test every variation through a browser and a full E2E flow.

## 21. Tests in CI/CD

A typical pipeline runs cheap checks first and expensive checks later:

```text
Commit
  -> Unit tests
  -> Integration tests
  -> Build/package
  -> Deployment
  -> Smoke tests
  -> Regression / critical E2E
  -> Release
```

Each stage can stop the pipeline when it fails. Smoke tests are especially useful as a post-deployment quality gate.

## 22. One feature through all test levels

Consider a bank transfer.

- **Unit:** `TransferService` verifies that PLN 1,000 minus PLN 200 equals PLN 800.
- **Component:** `POST /transfers` is checked through `TransferController`, JSON and HTTP 201; the service may be mocked.
- **Integration:** `TransferService`, `TransferRepository`, Hibernate and PostgreSQL are exercised together.
- **API/System:** HTTP, Spring Boot, security, service and PostgreSQL operate as a real application.
- **E2E:** the user logs in, opens the transfer page, enters data, confirms it and sees the new balance.
- **Smoke:** login, transfer and balance reading are checked.
- **Regression:** domestic transfer, international transfer, cancellation, history, balance and limits are rechecked after a change.
- **Performance:** 1,000 concurrent transfer requests are measured.
- **Security:** the test verifies that user A cannot transfer money from user B's account.

## 23. The complete map

```text
UNIT        Does one piece of logic work?
COMPONENT   Does one application slice work?
INTEGRATION Do components cooperate correctly?
SYSTEM      Does the integrated system work?
E2E         Does the complete user journey work?
ACCEPTANCE  Does the solution meet business needs?
SMOKE       Are the basic functions alive?
SANITY      Does the changed area work?
REGRESSION  Did the change break old functionality?
RE-TEST     Is the specific bug fixed?
PERFORMANCE How does the system behave under load?
SECURITY    Are the protection mechanisms effective?
```

This is why smoke and regression should not be placed in the same linear sequence as unit, integration and E2E.

## 24. Quick reference

| Test type | Main question |
|---|---|
| Unit | Does one class or piece of logic work? |
| Component / Slice | Does one application slice work? |
| Integration | Do components cooperate correctly? |
| API | Does the public API behave correctly? |
| Contract | Do two systems agree on their communication contract? |
| System | Does the complete integrated system work? |
| E2E | Does the full user journey work from start to finish? |
| Acceptance / UAT | Does the solution meet business requirements? |
| Smoke | Do the basic functions work at all? |
| Sanity | Does the changed area work after a change? |
| Regression | Did the new change break old functionality? |
| Re-test | Has the specific bug actually disappeared? |
| Load | Can the system handle expected traffic? |
| Stress | Where is the system's limit? |
| Spike | How does it react to a sudden traffic increase? |
| Soak | Is it stable over a long period? |
| Security | Do the security mechanisms work correctly? |

For a typical Java + Spring Boot application, a sensible technical progression is:

```text
JUnit + Mockito
        -> Unit
@WebMvcTest / MockMvc
        -> Component / API slice
@SpringBootTest
        -> Integration
Testcontainers
        -> Real PostgreSQL / Redis / Kafka integration
Real HTTP with Spring Boot
        -> System / API
Selenium or Playwright
        -> E2E
```

Spring Boot supports MVC-layer testing with MockMvc, full-server tests and Testcontainers integration with real backend services. The test level should match the risk being checked, while the CI pipeline should keep fast feedback at the beginning and reserve expensive checks for later stages.
