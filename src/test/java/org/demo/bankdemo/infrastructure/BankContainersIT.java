package org.demo.bankdemo.infrastructure;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class BankContainersIT {

    private static final PostgreSQLContainer POSTGRES;
    private static final RedisContainer REDIS;

    static {
        var image = DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres");
        POSTGRES = new PostgreSQLContainer(image);
        REDIS = new RedisContainer(DockerImageName.parse("redis:6.2.6"));
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Test
    void runsFlywayMigrationsAndUsesRedis() {
        var migrationCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history", Integer.class);
        assertTrue(migrationCount != null && migrationCount > 0);

        var key = "integration-test:bank:redis";
        redisTemplate.opsForValue().set(key, "connected");
        assertEquals("connected", redisTemplate.opsForValue().get(key));
        redisTemplate.delete(key);
    }
}
