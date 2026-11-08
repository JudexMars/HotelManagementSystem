package dev.hotelbooking.booking.client
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.hotelbooking.booking.application.port.HotelInventory
import dev.hotelbooking.booking.application.port.HotelRoomSearch
import dev.hotelbooking.booking.application.port.InventoryCandidate
import dev.hotelbooking.booking.application.port.InventoryResult
import dev.hotelbooking.booking.application.port.InventoryRoom
import dev.hotelbooking.booking.application.port.InventoryStatus
import dev.hotelbooking.booking.configuration.HotelClientProperties
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import dev.hotelbooking.booking.security.ServiceTokenProvider
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.springframework.cloud.client.discovery.DiscoveryClient
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.util.UUID

@Component
class HotelHttpClient(
    private val discovery: DiscoveryClient,
    private val properties: HotelClientProperties,
    private val tokens: ServiceTokenProvider,
) : HotelInventory,
    HotelRoomSearch {
    private val client =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(properties.connectTimeout)
                    setReadTimeout(properties.readTimeout)
                },
            ).build()

    private val mapper = jacksonObjectMapper()
    private val circuit =
        CircuitBreaker.of(
            "hotel-inventory",
            CircuitBreakerConfig
                .custom()
                .slidingWindowSize(properties.circuitWindow)
                .minimumNumberOfCalls(properties.circuitMinimumCalls)
                .failureRateThreshold(properties.circuitFailureRate)
                .waitDurationInOpenState(properties.circuitOpenWait)
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreException { it is BookingException && it.reason !in transientErrors }
                .build(),
        )

    init {
        require(properties.connectTimeout.toMillis() > 0 && properties.readTimeout.toMillis() > 0)
    }

    private fun base(): String =
        properties.baseUrl?.trimEnd('/')
            ?: discovery
                .getInstances("hotel-service")
                .firstOrNull()
                ?.uri
                ?.toString()
            ?: throw BookingException(BookingError.HOTEL_UNAVAILABLE)

    override fun room(
        id: UUID,
        traceId: String?,
    ): InventoryRoom =
        call {
            client
                .get()
                .uri("${base()}/internal/rooms/$id")
                .headers {
                    it.setBearerAuth(tokens.serviceToken())
                    traceId?.let { trace -> it.set("X-Trace-Id", trace) }
                }.retrieve()
                .body(RoomPayload::class.java)
                ?.let { InventoryRoom(it.id, it.hotelId) }
                ?: throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
        }

    override fun candidates(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
        traceId: String?,
    ): List<InventoryCandidate> =
        call(catalog = true) {
            val filter = hotelId?.let { "&hotelId=$it" }.orEmpty()
            client
                .get()
                .uri("${base()}/internal/rooms?startDate=$startDate&endDate=$endDate$filter")
                .headers {
                    it.setBearerAuth(tokens.serviceToken())
                    traceId?.let { trace -> it.set("X-Trace-Id", trace) }
                }.retrieve()
                .body(object : ParameterizedTypeReference<List<CandidatePayload>>() {})
                ?.map { InventoryCandidate(it.id, it.hotelId, it.number, it.available, it.timesBooked) }
                ?: throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
        }

    override fun hold(booking: Booking): InventoryResult = command(booking, "hold")

    override fun commit(booking: Booking): InventoryResult = command(booking, "commit")

    override fun release(booking: Booking): InventoryResult = command(booking, "release")

    private fun command(
        booking: Booking,
        action: String,
    ): InventoryResult =
        call {
            client
                .post()
                .uri("${base()}/internal/rooms/${requireNotNull(booking.roomId)}/$action")
                .headers {
                    it.setBearerAuth(tokens.serviceToken())
                    it.set("X-Trace-Id", booking.traceId)
                }.body(
                    mapOf(
                        "attemptId" to booking.attemptId,
                        "bookingId" to booking.id,
                        "startDate" to booking.startDate,
                        "endDate" to booking.endDate,
                    ),
                ).retrieve()
                .body(ReservationPayload::class.java)
                ?: throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
        }.toInventoryResult()

    private fun <T> call(
        catalog: Boolean = false,
        action: () -> T,
    ): T =
        try {
            circuit.executeSupplier {
                try {
                    action()
                } catch (
                    error: RestClientResponseException,
                ) {
                    throw remoteError(error, catalog)
                } catch (error: ResourceAccessException) {
                    val timeout = generateSequence<Throwable>(error) { it.cause }.any { it is SocketTimeoutException }
                    throw BookingException(if (timeout) BookingError.HOTEL_TIMEOUT else BookingError.HOTEL_UNAVAILABLE)
                } catch (error: RestClientException) {
                    // POST response extraction may wrap a socket timeout in RestClientException rather than ResourceAccessException.
                    val timeout = generateSequence<Throwable>(error) { it.cause }.any { it is SocketTimeoutException }
                    throw BookingException(if (timeout) BookingError.HOTEL_TIMEOUT else BookingError.INVENTORY_PROTOCOL_ERROR)
                }
            }
        } catch (_: CallNotPermittedException) {
            throw BookingException(BookingError.HOTEL_CIRCUIT_OPEN)
        }

    private fun remoteError(
        error: RestClientResponseException,
        catalog: Boolean,
    ): BookingException {
        val status = error.statusCode.value()
        if (status >= 500 || status == 429) return BookingException(BookingError.HOTEL_UNAVAILABLE)
        val code = runCatching { mapper.readTree(error.responseBodyAsByteArray).path("code").asText() }.getOrNull()
        val reason =
            when (status) {
                401, 403 -> BookingError.HOTEL_ACCESS_DENIED
                404 ->
                    when (code) {
                        "ROOM_NOT_FOUND" -> BookingError.ROOM_NOT_FOUND
                        "HOTEL_NOT_FOUND" -> if (catalog) BookingError.HOTEL_NOT_FOUND else BookingError.ROOM_UNAVAILABLE
                        else -> BookingError.INVENTORY_PROTOCOL_ERROR
                    }
                409 ->
                    when (code) {
                        "ROOM_UNAVAILABLE" -> BookingError.ROOM_UNAVAILABLE
                        "HOLD_EXPIRED" -> BookingError.HOLD_EXPIRED
                        "ATTEMPT_CONFLICT" -> BookingError.ATTEMPT_CONFLICT
                        "ATTEMPT_RELEASED" -> BookingError.ATTEMPT_RELEASED
                        else -> BookingError.INVENTORY_PROTOCOL_ERROR
                    }
                else -> BookingError.INVENTORY_PROTOCOL_ERROR
            }
        return BookingException(reason)
    }

    private companion object {
        val transientErrors = setOf(BookingError.HOTEL_UNAVAILABLE, BookingError.HOTEL_TIMEOUT)
    }
}

private data class RoomPayload(
    val id: UUID,
    val hotelId: UUID,
)

private data class ReservationPayload(
    val attemptId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String,
) {
    fun toInventoryResult(): InventoryResult {
        val inventoryStatus =
            InventoryStatus.entries.firstOrNull { it.name == status }
                ?: throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
        return InventoryResult(attemptId, bookingId, roomId, startDate, endDate, inventoryStatus)
    }
}

private data class CandidatePayload(
    val id: UUID,
    val hotelId: UUID,
    val number: String,
    val available: Boolean,
    val timesBooked: Long,
)
