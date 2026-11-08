package dev.hotelbooking.booking.configuration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class TimeConfiguration {
    @Bean fun clock(): Clock = Clock.systemUTC()
}
