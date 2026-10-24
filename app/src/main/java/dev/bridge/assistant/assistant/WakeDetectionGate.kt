package dev.bridge.assistant.assistant

import java.util.concurrent.atomic.AtomicBoolean

/** Allows one recorder worker at a time while making stop and rearm idempotent. */
internal class WakeDetectionGate {
    private val active = AtomicBoolean(false)

    fun begin(): Boolean = active.compareAndSet(false, true)
    fun stop(): Boolean = active.getAndSet(false)
    fun isActive(): Boolean = active.get()
}
