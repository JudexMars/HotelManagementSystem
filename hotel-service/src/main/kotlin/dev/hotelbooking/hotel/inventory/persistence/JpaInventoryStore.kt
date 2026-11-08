package dev.hotelbooking.hotel.inventory.persistence
import dev.hotelbooking.hotel.inventory.application.port.InventoryStore
import dev.hotelbooking.hotel.inventory.domain.InventoryCommand
import dev.hotelbooking.hotel.inventory.domain.Reservation
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.sql.SQLException

@Component
class JpaInventoryStore(
    private val transactions: InventoryTransactions,
) : InventoryStore {
    override fun commit(command: InventoryCommand): Reservation = transactions.commit(command)

    override fun hold(command: InventoryCommand): Reservation = retryUniqueConflict { transactions.hold(command) }

    override fun release(command: InventoryCommand): Reservation = retryUniqueConflict { transactions.release(command) }

    private fun retryUniqueConflict(action: () -> Reservation): Reservation =
        try {
            action()
        } catch (error: DataIntegrityViolationException) {
            if ((error.mostSpecificCause as? SQLException)?.sqlState != "23505") throw error
            // Retry only after the failed transaction has rolled back, to resolve the winning binding.
            action()
        }
}
