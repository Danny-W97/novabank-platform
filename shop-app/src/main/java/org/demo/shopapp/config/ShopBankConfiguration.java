package org.demo.shopapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({
        ShopBankProperties.class,
        ShopOrderProperties.class,
        ShopPaymentProperties.class,
        MetricsAccessProperties.class,
        ShopRateLimitProperties.class,
        CertificateMonitorProperties.class
})
public class ShopBankConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    RestClient.Builder bankRestClientBuilder() {
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    RestClient bankRestClient(RestClient.Builder builder, ShopBankProperties properties) {
        return builder.baseUrl(properties.getBaseUrl()).build();
    }
}
