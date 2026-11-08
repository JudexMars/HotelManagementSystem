package dev.hotelbooking.booking.configuration

import dev.hotelbooking.booking.identity.application.IdentityService
import dev.hotelbooking.booking.identity.application.UserAdministrationService
import dev.hotelbooking.booking.identity.application.port.PasswordHasher
import dev.hotelbooking.booking.identity.application.port.TokenIssuer
import dev.hotelbooking.booking.identity.application.port.UserStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {
    @Bean
    fun passwordHasher(): PasswordHasher =
        object : PasswordHasher {
            private val encoder = BCryptPasswordEncoder()

            override fun encode(password: String): String = encoder.encode(password)

            override fun matches(
                password: String,
                hash: String,
            ): Boolean = encoder.matches(password, hash)
        }

    @Bean
    fun identityService(
        users: UserStore,
        passwords: PasswordHasher,
        tokens: TokenIssuer,
    ) = IdentityService(users, passwords, tokens)

    @Bean
    fun userAdministrationService(
        users: UserStore,
        passwords: PasswordHasher,
    ): UserAdministrationService = UserAdministrationService(users, passwords)
}
