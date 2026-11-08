package dev.hotelbooking.hotel.inventory.api

import dev.hotelbooking.hotel.inventory.application.InventoryService
import dev.hotelbooking.hotel.inventory.domain.InventoryCommand
import dev.hotelbooking.hotel.inventory.domain.Reservation
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service

@Service
@PreAuthorize("hasAuthority('SCOPE_inventory:write')")
class AuthorizedInventoryService(
    private val delegate: InventoryService,
) {
    fun hold(command: InventoryCommand): Reservation = delegate.hold(command)

    fun commit(command: InventoryCommand): Reservation = delegate.commit(command)

    fun release(command: InventoryCommand): Reservation = delegate.release(command)
}
