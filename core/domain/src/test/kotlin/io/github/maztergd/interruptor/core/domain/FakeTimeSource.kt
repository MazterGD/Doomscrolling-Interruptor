package io.github.maztergd.interruptor.core.domain

/** A hand-cranked [TimeSource] so the engine can be tested in microseconds instead of minutes. */
internal class FakeTimeSource(private var nowMs: Long = 0L) : TimeSource {

    override fun elapsedRealtimeMs(): Long = nowMs

    fun advance(millis: Long) {
        require(millis >= 0) { "time cannot move backwards" }
        nowMs += millis
    }
}
