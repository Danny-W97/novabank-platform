package org.demo.bankdemo.maintenance;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MaintenanceCommandTest {
    @Test void rejectsDuplicateAndUnknownArguments() {
        assertThatThrownBy(() -> MaintenanceCommand.parse(new String[]{"--profile=dev", "--profile=prod"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaintenanceCommand.parse(new String[]{"--sql=delete"})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void databaseCannotBeRemoteOrOverrideConnectionParameters() {
        for (var url : new String[]{"jdbc:postgresql://remote.example:5432/test", "jdbc:postgresql://localhost:5432/test?user=other", "jdbc:postgresql://user:pass@localhost:5432/test", "jdbc:postgresql://localhost/test"}) {
            var env = Map.of("TEST_POSTGRES_URL", url, "TEST_POSTGRES_USER", "fixture", "TEST_POSTGRES_PASSWORD", "fixture");
            assertThatThrownBy(() -> MaintenanceCommand.dataSource("test", env)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
