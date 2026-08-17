package org.demo.bankdemo.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.bankdemo.config.RetentionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionExecutionServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RetentionRunRepository runRepository;

    @Test
    void dryRunCountsTechnicalDataAndDoesNotDeleteIt() {
        var properties = new RetentionProperties();
        var now = Instant.parse("2026-08-05T10:00:00Z");
        var service = new RetentionExecutionService(
                jdbcTemplate,
                properties,
                runRepository,
                Clock.fixed(now, ZoneOffset.UTC),
                new SimpleMeterRegistry()
        );
        when(runRepository.start(now, true)).thenReturn("run-1");
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        var result = service.execute(true);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.dryRun()).isTrue();
        assertThat(result.deletedCount()).isZero();
        verify(runRepository).finish(eq("run-1"), any(), eq("SUCCESS"), eq(0), eq(0), eq(0), anyString());
    }
}
