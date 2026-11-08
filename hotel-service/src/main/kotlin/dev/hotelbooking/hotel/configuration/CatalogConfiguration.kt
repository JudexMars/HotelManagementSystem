package dev.hotelbooking.hotel.configuration

import dev.hotelbooking.hotel.catalog.application.CatalogService
import dev.hotelbooking.hotel.catalog.application.port.CatalogStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {
    @Bean
    fun catalogService(store: CatalogStore): CatalogService = CatalogService(store)
}
