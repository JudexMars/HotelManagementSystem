package dev.hotelbooking.booking

import dev.hotelbooking.booking.application.CreateBooking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class BookingRequestIdentityTest {
    private val request = CreateBooking(true, UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3))

    @Test fun `auto selection ignores manual room while retaining hotel and dates`() {
        val original = request.identity("same-key")
        assertThat(request.copy(roomId = UUID.randomUUID()).identity("same-key")).isEqualTo(original)
        assertThat(request.copy(roomId = null).identity("same-key")).isEqualTo(original)
        assertThat(request.copy(hotelId = UUID.randomUUID()).identity("same-key")).isNotEqualTo(original)
        assertThat(request.copy(startDate = request.startDate.plusDays(1)).identity("same-key")).isNotEqualTo(original)
        assertThat(request.copy(endDate = request.endDate.plusDays(1)).identity("same-key")).isNotEqualTo(original)
        assertThat(request.copy(autoSelect = false).identity("same-key")).isNotEqualTo(original)
    }

    @Test fun `manual selection ignores hotel while retaining room and dates`() {
        val manual = request.copy(autoSelect = false)
        val original = manual.identity("same-key")
        assertThat(manual.copy(hotelId = UUID.randomUUID()).identity("same-key")).isEqualTo(original)
        assertThat(manual.copy(hotelId = null).identity("same-key")).isEqualTo(original)
        assertThat(manual.copy(roomId = UUID.randomUUID()).identity("same-key")).isNotEqualTo(original)
        assertThat(manual.copy(startDate = manual.startDate.plusDays(1)).identity("same-key")).isNotEqualTo(original)
        assertThat(manual.copy(endDate = manual.endDate.plusDays(1)).identity("same-key")).isNotEqualTo(original)
    }
}
