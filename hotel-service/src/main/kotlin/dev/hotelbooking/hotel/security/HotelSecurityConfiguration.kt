package dev.hotelbooking.hotel.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.web.http.ApiError
import dev.hotelbooking.web.security.JwtPolicy
import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
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
import java.time.Duration

@ConfigurationProperties("app.security")
data class RemoteKeyProperties(
    val jwkSetUri: String = "http://localhost:8081/api/users/jwks",
)

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(RemoteKeyProperties::class)
class HotelSecurityConfiguration {
    @Bean fun userJwtDecoder(
        properties: RemoteKeyProperties,
        clock: Clock,
    ): JwtDecoder = decoder(properties, clock, JwtPolicy.USER_AUDIENCE)

    @Bean fun serviceJwtDecoder(
        properties: RemoteKeyProperties,
        clock: Clock,
    ): JwtDecoder = decoder(properties, clock, JwtPolicy.SERVICE_AUDIENCE)

    private fun decoder(
        properties: RemoteKeyProperties,
        clock: Clock,
        audience: String,
    ): JwtDecoder =
        NimbusJwtDecoder
            .withJwkSetUri(properties.jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(RestTemplateBuilder().connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(2)).build())
            .build()
            .apply { setJwtValidator(JwtPolicy.validator(audience, clock)) }

    @Bean
    @Order(1)
    fun internalSecurity(
        http: HttpSecurity,
        @Qualifier("serviceJwtDecoder") decoder: JwtDecoder,
        mapper: ObjectMapper,
    ): SecurityFilterChain {
        http.securityMatcher("/internal/**").authorizeHttpRequests {
            it
                .requestMatchers(HttpMethod.GET, "/internal/rooms", "/internal/rooms/*")
                .hasAuthority("SCOPE_inventory:read")
                .requestMatchers(
                    HttpMethod.POST,
                    "/internal/rooms/*/hold",
                    "/internal/rooms/*/commit",
                    "/internal/rooms/*/release",
                ).hasAuthority("SCOPE_inventory:write")
                .anyRequest()
                .denyAll()
        }
        configureCommon(http, decoder, mapper, false)
        return http.build()
    }

    @Bean
    @Order(2)
    fun publicSecurity(
        http: HttpSecurity,
        @Qualifier("userJwtDecoder") decoder: JwtDecoder,
        mapper: ObjectMapper,
    ): SecurityFilterChain {
        http.authorizeHttpRequests {
            it
                .dispatcherTypeMatchers(
                    DispatcherType.ERROR,
                ).permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/health/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                .requestMatchers("/api/hotels/*/occupancy")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
                .hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
                .hasRole("ADMIN")
                .anyRequest()
                .denyAll()
        }
        configureCommon(http, decoder, mapper, true)
        return http.build()
    }

    private fun configureCommon(
        http: HttpSecurity,
        decoder: JwtDecoder,
        mapper: ObjectMapper,
        userRoles: Boolean,
    ) {
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
                if (userRoles) {
                    setAuthoritiesClaimName("roles")
                    setAuthorityPrefix("ROLE_")
                }
            }
        val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
        http.csrf { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.exceptionHandling { it.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden) }
        http.oauth2ResourceServer { resource ->
            resource
                .jwt { jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter) }
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(forbidden)
        }
    }
}
