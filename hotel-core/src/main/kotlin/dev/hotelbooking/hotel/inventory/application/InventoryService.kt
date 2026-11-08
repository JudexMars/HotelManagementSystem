package dev.hotelbooking.hotel.inventory.application
import dev.hotelbooking.hotel.inventory.application.port.InventoryStore
import dev.hotelbooking.hotel.inventory.domain.InventoryCommand
import dev.hotelbooking.hotel.inventory.domain.InventoryError
import dev.hotelbooking.hotel.inventory.domain.InventoryException
import dev.hotelbooking.hotel.inventory.domain.Reservation

class InventoryService(
    private val store: InventoryStore,
) {
    fun hold(command: InventoryCommand): Reservation {
        validate(command)
        return store.hold(command)
    }

    fun commit(command: InventoryCommand): Reservation {
        validate(command)
        return store.commit(command)
    }

    fun release(command: InventoryCommand): Reservation {
        validate(command)
        return store.release(command)
    }

    private fun validate(command: InventoryCommand) {
        if (!command.endDate.isAfter(command.startDate)) throw InventoryException(InventoryError.INVALID_PERIOD)
    }
}
