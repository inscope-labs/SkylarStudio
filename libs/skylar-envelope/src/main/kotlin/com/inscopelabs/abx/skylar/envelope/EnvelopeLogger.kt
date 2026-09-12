package com.inscopelabs.abx.skylar.envelope

/**
 * Pluggable logging interface for the shared envelope library.
 *
 * Allows consumers (Skylar Core, Starlight, SFM, test harnesses) to route
 * envelope library diagnostics to their own logging subsystem (e.g. Logger facade).
 */
fun interface EnvelopeLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, tag: String, message: String, throwable: Throwable?)
}

object EnvelopeLog {
    var delegate: EnvelopeLogger? = null

    fun d(tag: String, message: String) =
        delegate?.log(EnvelopeLogger.Level.DEBUG, tag, message, null)

    fun i(tag: String, message: String) =
        delegate?.log(EnvelopeLogger.Level.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        delegate?.log(EnvelopeLogger.Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        delegate?.log(EnvelopeLogger.Level.ERROR, tag, message, throwable)
}
