package dev.hotelbooking.booking.configuration
import dev.hotelbooking.booking.application.BookingQueries
import dev.hotelbooking.booking.application.BookingSagaRunner
import dev.hotelbooking.booking.application.CancelBookingUseCase
import dev.hotelbooking.booking.application.CreateBookingUseCase
import dev.hotelbooking.booking.application.RecommendRoomsUseCase
import dev.hotelbooking.booking.application.SagaPolicy
import dev.hotelbooking.booking.application.port.BookingEvents
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.application.port.HotelInventory
import dev.hotelbooking.booking.application.port.HotelRoomSearch
import dev.hotelbooking.booking.application.port.RetryPause
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("booking.hotel")
data class HotelClientProperties(
    val baseUrl: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(1),
    val readTimeout: Duration = Duration.ofSeconds(2),
    val circuitWindow: Int = 10,
    val circuitMinimumCalls: Int = 4,
    val circuitFailureRate: Float = 50f,
    val circuitOpenWait: Duration = Duration.ofSeconds(10),
)

@ConfigurationProperties("booking.saga")
data class SagaProperties(
    val budget: Duration = Duration.ofSeconds(20),
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(100),
    val lease: Duration = Duration.ofSeconds(5),
    val recoveryBackoff: Duration = Duration.ofSeconds(1),
    val maxRecoveryBackoff: Duration = Duration.ofSeconds(30),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HotelClientProperties::class, SagaProperties::class)
class BookingConfiguration {
    @Bean fun sagaPolicy(properties: SagaProperties) =
        SagaPolicy(
            properties.budget,
            properties.maxAttempts,
            properties.initialBackoff,
            properties.lease,
            properties.recoveryBackoff,
            properties.maxRecoveryBackoff,
        )

    @Bean fun retryPause() = RetryPause { Thread.sleep(it.toMillis()) }

    @Bean fun recommendRooms(search: HotelRoomSearch) = RecommendRoomsUseCase(search)

    @Bean fun bookingQueries(store: BookingStore) = BookingQueries(store)

    @Bean fun bookingSagaRunner(
        store: BookingStore,
        hotel: HotelInventory,
        recommendations: RecommendRoomsUseCase,
        clock: Clock,
        policy: SagaPolicy,
        pause: RetryPause,
        events: BookingEvents,
    ) = BookingSagaRunner(store, hotel, recommendations, clock, policy, pause, events)

    @Bean fun createBooking(
        store: BookingStore,
        saga: BookingSagaRunner,
        clock: Clock,
        policy: SagaPolicy,
        pause: RetryPause,
        events: BookingEvents,
    ) = CreateBookingUseCase(store, saga, clock, policy, pause, events)

    @Bean fun cancelBooking(
        store: BookingStore,
        saga: BookingSagaRunner,
        events: BookingEvents,
    ) = CancelBookingUseCase(store, saga, events)
}
