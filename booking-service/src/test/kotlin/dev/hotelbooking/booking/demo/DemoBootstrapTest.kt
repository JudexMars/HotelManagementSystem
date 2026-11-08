package dev.hotelbooking.booking.demo

import dev.hotelbooking.booking.identity.application.port.PasswordHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "booking.recovery.enabled=false"])
@ActiveProfiles("demo")
class DemoBootstrapTest {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var encoder: PasswordHasher

    @Test fun `demo startup loads consistent historical data`() {
        val past = jdbc.queryForMap("select b.*, u.username from bookings b join users u on b.user_id=u.id")
        assertThat(past["ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000301")
        assertThat(past["ATTEMPT_ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000401")
        assertThat(past["ROOM_ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000201")
        assertThat(past["HOTEL_ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000101")
        assertThat(past["STATUS"]).isEqualTo("CONFIRMED")
        assertThat(past["STEP"]).isEqualTo("DONE")
        assertThat(past["COMPENSATION_STATUS"]).isEqualTo("NONE")
        assertThat(past["START_DATE"].toString()).isEqualTo("2020-01-10")
        assertThat(past["END_DATE"].toString()).isEqualTo("2020-01-13")
        assertThat(past["USERNAME"]).isEqualTo("demo-user")
        val hash = jdbc.queryForObject("select password from users where username='demo-user'", String::class.java)
        assertThat(encoder.matches("demo-user-password", requireNotNull(hash))).isTrue()
    }
}
