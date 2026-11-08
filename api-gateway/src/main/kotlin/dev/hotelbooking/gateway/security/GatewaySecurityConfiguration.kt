package dev.hotelbooking.gateway.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.web.http.ApiError
import dev.hotelbooking.web.http.TraceIds
import dev.hotelbooking.web.security.JwtPolicy
import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("app.security")
data class RemoteKeyProperties(
    val jwkSetUri: String = "http://localhost:8081/api/users/jwks",
)

@Configuration(proxyBeanMethods = false)
@EnableReactiveMethodSecurity
@EnableConfigurationProperties(RemoteKeyProperties::class)
class GatewaySecurityConfiguration {
    @Bean fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun jwtDecoder(
        properties: RemoteKeyProperties,
        clock: Clock,
    ): ReactiveJwtDecoder {
        val client = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(Duration.ofSeconds(2))
        return NimbusReactiveJwtDecoder
            .withJwkSetUri(properties.jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .webClient(WebClient.builder().clientConnector(ReactorClientHttpConnector(client)).build())
            .build()
            .apply { setJwtValidator(JwtPolicy.validator(JwtPolicy.USER_AUDIENCE, clock)) }
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun traceFilter(mapper: ObjectMapper): WebFilter =
        WebFilter { exchange, chain ->
            val traceId = TraceIds.resolve(exchange.request.headers.getFirst("X-Trace-Id"))
            exchange.attributes["traceId"] = traceId
            exchange.response.headers.set("X-Trace-Id", traceId)
            val traced = exchange.mutate().request { it.headers { headers -> headers.set("X-Trace-Id", traceId) } }.build()
            if (exchange.request.path.value() == "/internal" ||
                exchange.request.path
                    .value()
                    .startsWith("/internal/")
            ) {
                error(traced, mapper, HttpStatus.NOT_FOUND, "NOT_FOUND", "Ресурс не найден")
            } else {
                chain.filter(traced)
            }
        }

    @Bean
    fun security(
        http: ServerHttpSecurity,
        mapper: ObjectMapper,
    ): SecurityWebFilterChain {
        val unauthorized =
            ServerAuthenticationEntryPoint { exchange, _ ->
                exchange.response.headers.set("WWW-Authenticate", "Bearer")
                error(exchange, mapper, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Требуется действительный токен")
            }
        val forbidden =
            ServerAccessDeniedHandler { exchange, _ ->
                error(exchange, mapper, HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав")
            }
        val authorities =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName("roles")
                setAuthorityPrefix("ROLE_")
            }
        val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        http.requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
        http.authorizeExchange {
            it
                .pathMatchers(HttpMethod.POST, "/api/users/register", "/api/users/auth")
                .permitAll()
                .pathMatchers(
                    HttpMethod.GET,
                    "/api/users/jwks",
                    "/actuator/health/**",
                    "/health/hotel",
                    "/health/booking",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/webjars/**",
                ).permitAll()
                .pathMatchers("/api/users", "/api/users/**", "/api/hotels/*/occupancy")
                .hasRole("ADMIN")
                .pathMatchers(HttpMethod.GET, "/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
                .hasAnyRole("USER", "ADMIN")
                .pathMatchers("/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
                .hasRole("ADMIN")
                .pathMatchers("/api/**")
                .hasAnyRole("USER", "ADMIN")
                .anyExchange()
                .denyAll()
        }
        http.exceptionHandling { it.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden) }
        http.oauth2ResourceServer { resource ->
            resource
                .jwt { it.jwtAuthenticationConverter(ReactiveJwtAuthenticationConverterAdapter(converter)) }
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(forbidden)
        }
        return http.build()
    }

    private fun error(
        exchange: ServerWebExchange,
        mapper: ObjectMapper,
        status: HttpStatus,
        code: String,
        message: String,
    ): Mono<Void> {
        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val bytes = mapper.writeValueAsBytes(ApiError(code, message, exchange.getAttribute<String>("traceId").orEmpty()))
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }
}
