package dev.hotelbooking.hotel.configuration
import dev.hotelbooking.hotel.catalog.application.port.CatalogStore
import dev.hotelbooking.hotel.inventory.application.InventoryService
import dev.hotelbooking.hotel.inventory.application.RoomSearchService
import dev.hotelbooking.hotel.inventory.application.port.InventoryStore
import dev.hotelbooking.hotel.inventory.application.port.RoomSearchStore
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryProperties::class)
class InventoryConfiguration {
    @Bean
    fun inventoryService(store: InventoryStore): InventoryService = InventoryService(store)

    @Bean
    fun roomSearchService(
        store: RoomSearchStore,
        clock: Clock,
        catalog: CatalogStore,
    ): RoomSearchService = RoomSearchService(store, clock, catalog)
}
