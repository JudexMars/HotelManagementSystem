package dev.hotelbooking.booking.identity.application.port

import dev.hotelbooking.booking.identity.domain.IssuedToken
import dev.hotelbooking.booking.identity.domain.UserProfile

interface TokenIssuer {
    fun issue(user: UserProfile): IssuedToken
}
