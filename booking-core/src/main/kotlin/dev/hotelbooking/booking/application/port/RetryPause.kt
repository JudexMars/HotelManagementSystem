package dev.hotelbooking.booking.application.port

import java.time.Duration

fun interface RetryPause {
    fun pause(duration: Duration)
}
