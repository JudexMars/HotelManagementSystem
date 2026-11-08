package dev.hotelbooking.booking

import dev.hotelbooking.booking.application.port.BookingClaim
import dev.hotelbooking.booking.application.port.BookingClaimLost
import dev.hotelbooking.booking.application.port.BookingRequestIdentity
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.BookingStep
import dev.hotelbooking.booking.domain.CompensationStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "booking.recovery.enabled=false"])
@Import(BookingLeaseTest.Configuration::class)
class BookingLeaseTest {
    @Autowired lateinit var store: BookingStore

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var clock: TestClock

    @Test fun `stale version cannot advance the same still live claim`() {
        val original = create()
        val prepared = store.prepared(original, UUID.randomUUID())
        assertThat(prepared.version).isGreaterThan(original.version)
        assertThat(prepared.token).isEqualTo(original.token)
        assertThatThrownBy { store.held(original) }.isInstanceOf(BookingClaimLost::class.java)
        assertThat(store.find(original.booking.id)?.step).isEqualTo(BookingStep.HOLD)
        assertThat(store.held(prepared).booking.step).isEqualTo(BookingStep.COMMIT)
    }

    @Test fun `expired owner cannot apply a response after takeover`() {
        val original = create(lease = Duration.ofSeconds(1))
        clock.now = clock.now.plusSeconds(2)
        val successor = requireNotNull(store.claim(original.booking.id, Duration.ofSeconds(5)))
        assertThat(successor.token).isNotEqualTo(original.token)
        assertThat(successor.booking.attemptId).isEqualTo(original.booking.attemptId)
        assertThatThrownBy { store.confirmed(original) }.isInstanceOf(BookingClaimLost::class.java)
        val prepared = store.prepared(successor, UUID.randomUUID())
        val held = store.held(prepared)
        assertThat(store.confirmed(held).booking.status).isEqualTo(BookingStatus.CONFIRMED)
    }

    @Test fun `expired booking budget invalidates even an otherwise live owner`() {
        val original = create(budget = Duration.ofSeconds(1))
        clock.now = clock.now.plusSeconds(2)
        val compensation = requireNotNull(store.claim(original.booking.id, Duration.ofSeconds(5)))
        assertThat(compensation.booking.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(compensation.booking.compensationStatus).isEqualTo(CompensationStatus.PENDING)
        assertThatThrownBy { store.confirmed(original) }.isInstanceOf(BookingClaimLost::class.java)
        assertThat(store.compensated(compensation).booking.compensationStatus).isEqualTo(CompensationStatus.DONE)
    }

    private fun create(
        lease: Duration = Duration.ofSeconds(5),
        budget: Duration = Duration.ofSeconds(20),
    ): BookingClaim {
        val user = UUID.randomUUID()
        jdbc.update(
            "insert into users(id, username, password, role, deleted, version) values (?, ?, 'fixture', 'USER', false, 0)",
            user,
            user.toString(),
        )
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        return store.create(
            user,
            UUID.randomUUID(),
            today.plusDays(1),
            today.plusDays(2),
            clock.instant(),
            clock.instant().plus(budget),
            "lease-test",
            lease,
            BookingRequestIdentity(UUID.randomUUID().toString(), "lease-test"),
        )
    }

    class TestClock : Clock() {
        var now: Instant = Instant.now()

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration class Configuration {
        @Bean @Primary
        fun leaseClock() = TestClock()
    }
}
