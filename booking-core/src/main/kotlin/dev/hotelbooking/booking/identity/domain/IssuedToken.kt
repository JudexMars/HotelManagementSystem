package dev.hotelbooking.booking.identity.domain

import java.time.Instant

class IssuedToken(
    val value: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
