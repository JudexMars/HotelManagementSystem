package dev.hotelbooking.booking.identity.domain

object UserValidation {
    fun username(value: String) {
        if (value.length !in 1..100 || value.any(Char::isWhitespace)) throw IdentityException(IdentityError.INVALID_USERNAME)
    }

    fun password(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size !in 8..72) throw IdentityException(IdentityError.INVALID_PASSWORD)
    }
}
