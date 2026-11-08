package dev.hotelbooking.booking.scheduling
import dev.hotelbooking.booking.application.BookingSagaRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["booking.recovery.enabled"], havingValue = "true", matchIfMissing = true)
class BookingRecoveryScheduler(
    private val saga: BookingSagaRunner,
) {
    @Scheduled(fixedDelayString = $$"${booking.recovery.interval-ms:1000}")
    fun recover() = saga.recoverDue()
}
