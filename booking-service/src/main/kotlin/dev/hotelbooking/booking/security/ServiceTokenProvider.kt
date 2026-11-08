package dev.hotelbooking.booking.security

/** Supplies credentials for outbound service calls without exposing signing keys or user token issuance. */
fun interface ServiceTokenProvider {
    fun serviceToken(): String
}
