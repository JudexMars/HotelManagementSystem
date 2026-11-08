package dev.hotelbooking.booking.identity.application

import dev.hotelbooking.booking.identity.domain.IssuedToken
import dev.hotelbooking.booking.identity.domain.UserProfile

class AuthenticatedUser(
    val user: UserProfile,
    val token: IssuedToken,
)
