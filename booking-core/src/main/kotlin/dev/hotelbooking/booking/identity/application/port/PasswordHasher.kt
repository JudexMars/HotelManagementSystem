package dev.hotelbooking.booking.identity.application.port

interface PasswordHasher {
    fun encode(password: String): String

    fun matches(
        password: String,
        hash: String,
    ): Boolean
}
