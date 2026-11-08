package dev.hotelbooking.booking.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.web.http.ApiError
import dev.hotelbooking.web.security.JwtPolicy
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class BookingSecurityConfiguration {
    @Bean fun tokenIssuer(clock: Clock) = JwtTokenIssuer(clock)

    @Bean
    fun jwtDecoder(
        issuer: JwtTokenIssuer,
        clock: Clock,
    ): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(issuer.publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build().apply {
            setJwtValidator(JwtPolicy.validator(JwtPolicy.USER_AUDIENCE, clock))
        }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        mapper: ObjectMapper,
    ): SecurityFilterChain {
        val unauthorized =
            AuthenticationEntryPoint { request, response, _ ->
                response.status = 401
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.setHeader("WWW-Authenticate", "Bearer")
                mapper.writeValue(
                    response.outputStream,
                    ApiError("UNAUTHORIZED", "Требуется действительный токен", request.getAttribute("traceId").toString()),
                )
            }
        val forbidden =
            AccessDeniedHandler { request, response, _ ->
                response.status = 403
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                mapper.writeValue(
                    response.outputStream,
                    ApiError("FORBIDDEN", "Недостаточно прав", request.getAttribute("traceId").toString()),
                )
            }
        val authorities =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName("roles")
                setAuthorityPrefix("ROLE_")
            }
        val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
        http.authorizeHttpRequests {
            it
                .dispatcherTypeMatchers(
                    DispatcherType.ERROR,
                ).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/users/register", "/api/users/auth")
                .permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/users/jwks",
                    "/actuator/health/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                .requestMatchers("/api/users", "/api/users/**")
                .hasRole("ADMIN")
                .requestMatchers("/api/bookings", "/api/bookings/**")
                .hasAnyRole("USER", "ADMIN")
                .anyRequest()
                .denyAll()
        }
        http.csrf { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.exceptionHandling { it.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden) }
        http.oauth2ResourceServer { resource ->
            resource
                .jwt { jwt -> jwt.jwtAuthenticationConverter(converter) }
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(forbidden)
        }
        return http.build()
    }
}
