package dev.hotelbooking.hotel.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("inventory")
data class InventoryProperties(
    val holdTtl: Duration = Duration.ofSeconds(30),
) {
    init {
        require(holdTtl.toMillis() > 0) { "inventory.hold-ttl must be at least one millisecond" }
    }
}
