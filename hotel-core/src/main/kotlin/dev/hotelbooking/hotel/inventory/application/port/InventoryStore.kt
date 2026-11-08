package dev.hotelbooking.hotel.inventory.application.port

import dev.hotelbooking.hotel.inventory.domain.InventoryCommand
import dev.hotelbooking.hotel.inventory.domain.Reservation

interface InventoryStore {
    fun release(command: InventoryCommand): Reservation

    fun commit(command: InventoryCommand): Reservation

    fun hold(command: InventoryCommand): Reservation
}
