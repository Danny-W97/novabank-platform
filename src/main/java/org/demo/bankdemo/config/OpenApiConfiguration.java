package org.demo.bankdemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI bankOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NovaBank API")
                        .version("v1")
                        .description("Kontrakt HTTP API aplikacji NovaBank."))
                .components(new Components()
                        .addSecuritySchemes("bankApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key"))
                        .addSecuritySchemes("bankApiKeyId", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key-Id")));
    }
}
