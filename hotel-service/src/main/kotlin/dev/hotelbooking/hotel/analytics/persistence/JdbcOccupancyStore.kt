package dev.hotelbooking.hotel.analytics.persistence

import dev.hotelbooking.hotel.analytics.application.port.OccupancyStore
import dev.hotelbooking.hotel.analytics.domain.OccupancySnapshot
import dev.hotelbooking.hotel.analytics.domain.RoomOccupancyTotals
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcOccupancyStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : OccupancyStore {
    @Transactional(readOnly = true)
    override fun read(
        hotelId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): OccupancySnapshot? {
        val parameters =
            MapSqlParameterSource("hotelId", hotelId)
                .addValue("periodStart", Date.valueOf(from))
                .addValue("periodEnd", Date.valueOf(to))
        val rows =
            jdbc.query(
                """
                select h.id as hotel_id, h.name as hotel_name, h.deleted as hotel_deleted,
                       r.id as room_id, r.number as room_number, r.deleted as room_deleted,
                       count(distinct v.attempt_id) as booking_count,
                       coalesce(sum(case when v.attempt_id is null then 0 else
                           datediff('DAY', greatest(v.start_date, :periodStart), least(v.end_date, :periodEnd))
                           end), 0) as booked_nights
                from hotels h
                left join rooms r on r.hotel_id = h.id
                left join reservations v on v.room_id = r.id and v.status = 'RESERVED'
                    and v.start_date < :periodEnd and v.end_date > :periodStart
                where h.id = :hotelId
                group by h.id, h.name, h.deleted, r.id, r.number, r.deleted
                """.trimIndent(),
                parameters,
            ) { row, _ ->
                val roomId = row.getObject("room_id", UUID::class.java)
                Row(
                    row.getObject("hotel_id", UUID::class.java),
                    row.getString("hotel_name"),
                    row.getBoolean("hotel_deleted"),
                    roomId?.let {
                        RoomOccupancyTotals(
                            it,
                            row.getString("room_number"),
                            row.getBoolean("room_deleted"),
                            row.getLong("booking_count"),
                            row.getLong("booked_nights"),
                        )
                    },
                )
            }
        val first = rows.firstOrNull() ?: return null
        return OccupancySnapshot(first.hotelId, first.hotelName, first.hotelDeleted, rows.mapNotNull { it.room })
    }

    private data class Row(
        val hotelId: UUID,
        val hotelName: String,
        val hotelDeleted: Boolean,
        val room: RoomOccupancyTotals?,
    )
}
