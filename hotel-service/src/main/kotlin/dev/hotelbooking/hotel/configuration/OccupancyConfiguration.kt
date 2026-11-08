package dev.hotelbooking.hotel.configuration

import dev.hotelbooking.hotel.analytics.application.OccupancyService
import dev.hotelbooking.hotel.analytics.application.port.OccupancyStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OccupancyConfiguration {
    @Bean fun occupancyService(store: OccupancyStore) = OccupancyService(store)
}
