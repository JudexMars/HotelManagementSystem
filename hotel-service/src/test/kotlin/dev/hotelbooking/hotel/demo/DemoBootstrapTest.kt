package dev.hotelbooking.hotel.demo

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

    @Test fun `demo startup loads consistent historical data`() {
        assertThat(jdbc.queryForObject("select count(*) from hotels", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from rooms", Long::class.java)).isEqualTo(4)
        assertThat(jdbc.queryForObject("select count(*) from rooms where available = false", Long::class.java)).isEqualTo(1)
        val past = jdbc.queryForMap("select * from reservations")
        assertThat(past["BOOKING_ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000301")
        assertThat(past["ATTEMPT_ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000401")
        assertThat(past["ROOM_ID"].toString()).isEqualTo("00000000-0000-0000-0000-000000000201")
        assertThat(past["STATUS"]).isEqualTo("RESERVED")
        assertThat(past["START_DATE"].toString()).isEqualTo("2020-01-10")
        assertThat(past["END_DATE"].toString()).isEqualTo("2020-01-13")
        assertThat(
            jdbc.queryForObject(
                """
                select count(*) from rooms r where r.times_booked <>
                    (select count(*) from reservations v where v.room_id=r.id and v.status='RESERVED')
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }
}
