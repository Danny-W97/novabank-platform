package org.demo.shopapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class ShopTestConfigurationTest {
    @Test void defaultTestKeyIdMatchesTheBanksSingleKeyConfiguration() throws Exception {
        var source = new YamlPropertySourceLoader().load("isolated-test-profile",
                new ClassPathResource("application-test.yaml")).getFirst();
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(source);
        assertThat(environment.getProperty("shop.bank.api-key-id")).isEqualTo("legacy");
        assertThat(environment.getProperty("shop.orders.merchant-id")).isEqualTo("synthetic-merchant");
    }
}
