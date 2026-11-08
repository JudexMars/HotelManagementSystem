package dev.hotelbooking.booking.application

import java.time.Duration

data class SagaPolicy(
    val budget: Duration,
    val maxAttempts: Int,
    val initialBackoff: Duration,
    val lease: Duration = Duration.ofSeconds(5),
    val recoveryBackoff: Duration = Duration.ofSeconds(1),
    val maxRecoveryBackoff: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!budget.isNegative && !budget.isZero)
        require(maxAttempts in 1..10)
        require(lease.toMillis() > 0 && recoveryBackoff.toMillis() > 0 && maxRecoveryBackoff >= recoveryBackoff)
        require(!initialBackoff.isNegative && !initialBackoff.isZero)
    }
}
