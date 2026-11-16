package org.demo.shopapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcRepositoriesAutoConfiguration,"
                + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
@Import(ShopAppApplicationIT.TestBeans.class)
class ShopAppApplicationIT {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void contextLoads() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }

}
