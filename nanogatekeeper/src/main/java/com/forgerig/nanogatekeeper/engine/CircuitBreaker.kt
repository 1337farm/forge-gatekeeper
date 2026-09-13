package com.forgerig.nanogatekeeper.engine

class CircuitBreaker(
    private val failureThreshold: Int,
    private val cooldownMs: Long
) {
    private var failures = 0
    private var openedAt = 0L

    @Synchronized
    fun canExecute(now: Long = System.currentTimeMillis()): Boolean {
        if (failures < failureThreshold) return true
        return (now - openedAt) >= cooldownMs
    }

    @Synchronized
    fun recordSuccess() {
        failures = 0
        openedAt = 0L
    }

    @Synchronized
    fun recordFailure(now: Long = System.currentTimeMillis()) {
        failures++
        if (failures >= failureThreshold) openedAt = now
    }

    @Synchronized
    fun isOpen(now: Long = System.currentTimeMillis()): Boolean =
        failures >= failureThreshold && (now - openedAt) < cooldownMs
}
