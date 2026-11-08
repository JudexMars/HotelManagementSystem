package dev.hotelbooking.booking.client

import com.sun.net.httpserver.HttpServer
import dev.hotelbooking.booking.configuration.HotelClientProperties
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingException
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.BookingStep
import dev.hotelbooking.booking.domain.CompensationStatus
import dev.hotelbooking.booking.security.JwtTokenIssuer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.cloud.client.discovery.DiscoveryClient
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class HotelHttpClientTest {
    @Test fun `circuit opens after repeated unavailable responses and prevents another request`() {
        server(503, "HOTEL_UNAVAILABLE") { client, requests ->
            repeat(5) { failure { client.room(UUID.randomUUID()) } }
            assertThat(requests.get()).isEqualTo(4)
        }
    }

    @Test fun `business conflict preserves code and does not open the circuit`() {
        server(409, "ROOM_UNAVAILABLE") { client, requests ->
            repeat(10) { assertThat(failure { client.hold(booking()) }).isEqualTo("ROOM_UNAVAILABLE") }
            assertThat(requests.get()).isEqualTo(10)
        }
    }

    @Test fun `only authoritative missing room code can certify absent inventory`() {
        server(404, "ROOM_NOT_FOUND") { client, _ ->
            assertThat(failure { client.release(booking()) }).isEqualTo("ROOM_NOT_FOUND")
        }
        server(404, "NOT_FOUND") { client, _ ->
            assertThat(failure { client.release(booking()) }).isEqualTo("INVENTORY_PROTOCOL_ERROR")
        }
    }

    @Test fun `read timeout retains timeout classification`() {
        server(200, "unused", 2300) { client, _ ->
            assertThat(failure { client.commit(booking()) }).isEqualTo("HOTEL_TIMEOUT")
        }
    }

    @Test fun `service credential rejection is an upstream error rather than a user authorization failure`() {
        for (status in listOf(401, 403)) {
            server(status, "FORBIDDEN") { client, requests ->
                assertThat(failure { client.candidates(LocalDate.now(), LocalDate.now().plusDays(1), null, "search-test") })
                    .isEqualTo("HOTEL_ACCESS_DENIED")
                assertThat(requests.get()).isEqualTo(1)
            }
        }
    }

    @Test fun `unknown hotel in search stays distinct from authoritative missing room during release`() {
        server(404, "HOTEL_NOT_FOUND") { client, _ ->
            assertThat(failure { client.candidates(LocalDate.now(), LocalDate.now().plusDays(1), UUID.randomUUID(), "search-test") })
                .isEqualTo("HOTEL_NOT_FOUND")
            assertThat(failure { client.release(booking()) }).isEqualTo("ROOM_UNAVAILABLE")
        }
    }

    @Test fun `inventory response retains exact binding and supported status`() {
        val expected = booking()
        for (status in listOf("HELD", "RESERVED", "RELEASED", "EXPIRED")) {
            server(200, "unused", responseBody = reservationPayload(expected, status)) { client, requests ->
                val actual = client.hold(expected)
                assertThat(actual.attemptId).isEqualTo(expected.attemptId)
                assertThat(actual.bookingId).isEqualTo(expected.id)
                assertThat(actual.roomId).isEqualTo(expected.roomId)
                assertThat(actual.startDate).isEqualTo(expected.startDate)
                assertThat(actual.endDate).isEqualTo(expected.endDate)
                assertThat(actual.status.toString()).isEqualTo(status)
                assertThat(requests.get()).isEqualTo(1)
            }
        }
    }

    @Test fun `unknown inventory status is a protocol error without opening the circuit`() {
        val expected = booking()
        for (status in listOf("UNKNOWN", "reserved", "")) {
            server(200, "unused", responseBody = reservationPayload(expected, status)) { client, requests ->
                repeat(8) {
                    assertThat(failure { client.commit(expected) }).isEqualTo("INVENTORY_PROTOCOL_ERROR")
                }
                assertThat(requests.get()).isEqualTo(8)
            }
        }
    }

    private fun reservationPayload(
        booking: Booking,
        status: String,
    ): String =
        """{"attemptId":"${booking.attemptId}","bookingId":"${booking.id}","roomId":"${booking.roomId}",
        "startDate":"${booking.startDate}","endDate":"${booking.endDate}","status":"$status"}"""

    private fun failure(action: () -> Any): String {
        try {
            action()
            error("Expected remote failure")
        } catch (error: BookingException) {
            return error.reason.name
        }
    }

    private fun server(
        status: Int,
        code: String,
        delayMillis: Long = 0,
        responseBody: String? = null,
        test: (HotelHttpClient, AtomicInteger) -> Unit,
    ) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/rooms") { exchange ->
            exchange.use {
                requests.incrementAndGet()
                if (delayMillis > 0) Thread.sleep(delayMillis)
                exchange.requestBody.readAllBytes()
                val bytes = (responseBody ?: """{"code":"$code","message":"test","traceId":"test"}""").toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val client =
                HotelHttpClient(
                    mock(DiscoveryClient::class.java),
                    HotelClientProperties("http://127.0.0.1:${server.address.port}"),
                    JwtTokenIssuer(Clock.systemUTC()),
                )
            test(client, requests)
        } finally {
            server.stop(0)
        }
    }

    private fun booking(): Booking {
        val today = LocalDate.now()
        return Booking(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            today,
            today.plusDays(1),
            Instant.now(),
            BookingStatus.PENDING,
            BookingStep.HOLD,
            UUID.randomUUID(),
            CompensationStatus.NONE,
            Instant.now().plusSeconds(20),
            null,
            "client-test",
        )
    }
}
