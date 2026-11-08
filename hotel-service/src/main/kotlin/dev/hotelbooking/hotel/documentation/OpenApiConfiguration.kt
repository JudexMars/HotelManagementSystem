package dev.hotelbooking.hotel.documentation

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("hotel-service").version("0.1.0"))
            .servers(listOf(Server().url("/")))
            .components(
                Components()
                    .addSecuritySchemes(
                        "bearerAuth",
                        SecurityScheme()
                            .type(
                                SecurityScheme.Type.HTTP,
                            ).scheme("bearer")
                            .bearerFormat("JWT")
                            .description("USER/ADMIN, audience hotel-booking-api"),
                    ).addSecuritySchemes(
                        "serviceAuth",
                        SecurityScheme()
                            .type(
                                SecurityScheme.Type.HTTP,
                            ).scheme("bearer")
                            .bearerFormat("JWT")
                            .description("Booking service only, audience hotel-inventory"),
                    ),
            ).addSecurityItem(SecurityRequirement().addList("bearerAuth"))
}
