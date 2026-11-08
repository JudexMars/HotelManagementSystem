package dev.hotelbooking.hotel.catalog.api
import com.fasterxml.jackson.annotation.JsonProperty
import dev.hotelbooking.hotel.catalog.domain.Hotel
import dev.hotelbooking.hotel.catalog.domain.Room
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

data class HotelRequest(
    val name: String,
    val address: String,
)

data class CreateRoomRequest(
    val hotelId: UUID,
    val number: String,
    val available: Boolean = true,
)

data class UpdateRoomRequest(
    val number: String,
    @param:JsonProperty(required = true) val available: Boolean,
)

data class HotelResponse(
    val id: UUID,
    val name: String,
    val address: String,
)

data class RoomResponse(
    val id: UUID,
    val hotelId: UUID,
    val number: String,
    val available: Boolean,
    val timesBooked: Long,
)

data class HotelPageResponse(
    val items: List<HotelResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)

@RestController
@RequestMapping("/api")
class CatalogController(
    private val service: AuthorizedCatalogService,
) {
    @DeleteMapping("/hotels/{id}")
    @ApiResponse(responseCode = "204", description = "Отель удалён")
    fun deleteHotel(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.deleteHotel(id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/rooms/{id}")
    @ApiResponse(responseCode = "204", description = "Номер удалён")
    fun deleteRoom(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.deleteRoom(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/hotels")
    @ApiResponse(responseCode = "201", description = "Отель создан", useReturnTypeSchema = true)
    fun createHotel(
        @RequestBody body: HotelRequest,
    ): ResponseEntity<HotelResponse> {
        val hotel = service.createHotel(body.name, body.address)
        return ResponseEntity.created(URI.create("/api/hotels/${hotel.id}")).body(hotel.response())
    }

    @GetMapping("/hotels/{id}")
    fun hotel(
        @PathVariable id: UUID,
    ): HotelResponse = service.hotel(id).response()

    @PutMapping("/hotels/{id}")
    fun updateHotel(
        @PathVariable id: UUID,
        @RequestBody body: HotelRequest,
    ): HotelResponse = service.updateHotel(id, body.name, body.address).response()

    @GetMapping("/hotels")
    fun hotels(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) address: String?,
    ): HotelPageResponse {
        val result = service.hotels(page, size, name, address)
        return HotelPageResponse(
            result.items.map { it.response() },
            page,
            size,
            result.total,
            (result.total + size - 1) / size,
        )
    }

    @PostMapping("/rooms")
    @ApiResponse(responseCode = "201", description = "Номер создан", useReturnTypeSchema = true)
    fun createRoom(
        @RequestBody body: CreateRoomRequest,
    ): ResponseEntity<RoomResponse> {
        val room = service.createRoom(body.hotelId, body.number, body.available)
        return ResponseEntity.created(URI.create("/api/rooms/${room.id}")).body(room.response())
    }

    @GetMapping("/rooms/{id}")
    fun room(
        @PathVariable id: UUID,
    ): RoomResponse = service.room(id).response()

    @PutMapping("/rooms/{id}")
    fun updateRoom(
        @PathVariable id: UUID,
        @RequestBody body: UpdateRoomRequest,
    ): RoomResponse = service.updateRoom(id, body.number, body.available).response()
}

private fun Hotel.response() = HotelResponse(id, name, address)

private fun Room.response() = RoomResponse(id, hotelId, number, available, timesBooked)
