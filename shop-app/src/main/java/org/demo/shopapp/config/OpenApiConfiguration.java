package org.demo.shopapp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI shopOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NovaTech Shop API")
                        .version("v1")
                        .description("Kontrakt HTTP API aplikacji NovaTech Shop."))
                .components(new Components()
                        .addSecuritySchemes("shopSession", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("SHOP_SESSION")));
    }
}
