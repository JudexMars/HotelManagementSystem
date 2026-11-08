package dev.hotelbooking.booking.demo

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.booking.identity.application.port.PasswordHasher
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
    private val encoder: PasswordHasher,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        val data = ClassPathResource("demo-session.json").inputStream.use { mapper.readTree(it) }
        val past = data.path("past")
        val createdAt = Instant.parse("2020-01-01T00:00:00Z")
        val user = data.path("user")
        val userId = UUID.fromString(user.path("id").asText())
        jdbc.update(
            "insert into users(id,username,password,role) values (?,?,?,'USER')",
            userId,
            user.path("username").asText(),
            encoder.encode("demo-user-password"),
        )
        jdbc.update(
            """insert into bookings(id,user_id,room_id,hotel_id,start_date,end_date,created_at,status,step,
               attempt_id,compensation_status,deadline,trace_id,next_action_at)
               values (?,?,?,?,?,?,?,'CONFIRMED','DONE',?,'NONE',?,'demo-history',?)""",
            UUID.fromString(past.path("bookingId").asText()),
            userId,
            UUID.fromString(past.path("roomId").asText()),
            UUID.fromString(past.path("hotelId").asText()),
            Date.valueOf(past.path("from").asText()),
            Date.valueOf(past.path("to").asText()),
            createdAt,
            UUID.fromString(past.path("attemptId").asText()),
            createdAt.plusSeconds(20),
            createdAt,
        )
    }
}
