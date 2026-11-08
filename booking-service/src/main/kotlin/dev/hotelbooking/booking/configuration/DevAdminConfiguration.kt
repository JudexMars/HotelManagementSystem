package dev.hotelbooking.booking.configuration

import dev.hotelbooking.booking.identity.application.IdentityService
import dev.hotelbooking.booking.identity.domain.Credentials
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@ConfigurationProperties("booking.demo.admin")
class DevAdminProperties(
    val username: String = "demo-admin",
    val password: String = "demo-admin-password",
)

@Configuration(proxyBeanMethods = false)
@Profile("dev")
@EnableConfigurationProperties(DevAdminProperties::class)
class DevAdminConfiguration {
    @Bean
    fun bootstrapAdmin(
        service: IdentityService,
        properties: DevAdminProperties,
    ) = ApplicationRunner {
        service.bootstrapAdmin(Credentials(properties.username, properties.password))
    }
}
