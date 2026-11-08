package dev.hotelbooking.hotel.demo

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.Instant
import java.util.UUID

/** Explicit demo-only import of a completed stay; public booking date validation remains unchanged. */
@Component
@Profile("demo")
class DemoBootstrap(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        val data = ClassPathResource("demo-session.json").inputStream.use { mapper.readTree(it) }
        val past = data.path("past")
        val createdAt = Instant.parse("2020-01-01T00:00:00Z")
        for (hotel in data.path("hotels")) {
            jdbc.update(
                "insert into hotels(id,name,address) values (?,?,?)",
                UUID.fromString(hotel.path("id").asText()),
                hotel.path("name").asText(),
                hotel.path("address").asText(),
            )
        }
        for (room in data.path("rooms")) {
            jdbc.update(
                "insert into rooms(id,hotel_id,number,available) values (?,?,?,?)",
                UUID.fromString(room.path("id").asText()),
                UUID.fromString(room.path("hotelId").asText()),
                room.path("number").asText(),
                room.path("available").asBoolean(),
            )
        }
        jdbc.update(
            """insert into reservations(attempt_id,booking_id,room_id,start_date,end_date,status,created_at)
               values (?,?,?,?,?,'RESERVED',?)""",
            UUID.fromString(past.path("attemptId").asText()),
            UUID.fromString(past.path("bookingId").asText()),
            UUID.fromString(past.path("roomId").asText()),
            Date.valueOf(past.path("from").asText()),
            Date.valueOf(past.path("to").asText()),
            createdAt,
        )
        // Rebuild counters for every seeded room, including rooms without reservations.
        jdbc.update(
            """-- noinspection SqlWithoutWhere
            update rooms r set times_booked =
            (select count(*) from reservations v where v.room_id=r.id and v.status='RESERVED')""",
        )
    }
}
