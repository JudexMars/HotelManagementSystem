package dev.hotelbooking.booking.observability
import dev.hotelbooking.booking.application.port.BookingEvents
import dev.hotelbooking.booking.application.port.SagaEvent
import dev.hotelbooking.booking.domain.Booking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

private val logger = LoggerFactory.getLogger("dev.hotelbooking.booking.saga")

@Component
class StructuredBookingEvents : BookingEvents {
    override fun record(
        event: SagaEvent,
        booking: Booking,
    ) {
        val previousTrace = MDC.get("traceId")
        try {
            // Boot includes MDC in structured JSON; adding the same key again would discard the log event.
            MDC.put("traceId", booking.traceId)
            logger
                .atInfo()
                .addKeyValue("event", event.name)
                .addKeyValue("bookingId", booking.id)
                .addKeyValue("attemptId", booking.attemptId)
                .addKeyValue("roomId", booking.roomId)
                .addKeyValue("hotelId", booking.hotelId)
                .addKeyValue("status", booking.status)
                .addKeyValue("step", booking.step)
                .addKeyValue("compensationStatus", booking.compensationStatus)
                .addKeyValue("failureCode", booking.failureCode)
                .log("Booking saga transition")
        } finally {
            if (previousTrace == null) MDC.remove("traceId") else MDC.put("traceId", previousTrace)
        }
    }
}
