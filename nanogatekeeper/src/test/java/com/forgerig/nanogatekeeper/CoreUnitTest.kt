package com.forgerig.nanogatekeeper

import com.forgerig.nanogatekeeper.engine.CircuitBreaker
import com.forgerig.nanogatekeeper.engine.TokenEstimator
import com.forgerig.nanogatekeeper.model.GatekeeperConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUnitTest {

    @Test
    fun `token count minimum one`() {
        assertEquals(1, TokenEstimator.count(""))
        assertEquals(1, TokenEstimator.count("abc"))
        assertEquals(25, TokenEstimator.count("a".repeat(100)))
    }

    @Test
    fun `compression ratio math`() {
        assertEquals(50.0, TokenEstimator.ratio(100, 50), 1e-9)
        assertEquals(0.0, TokenEstimator.ratio(0, 0), 1e-9)
        assertEquals(0.0, TokenEstimator.ratio(100, 100), 1e-9)
        assertEquals(100.0, TokenEstimator.ratio(100, 0), 1e-9)
    }

    @Test
    fun `circuit breaker trips and cools down`() {
        val cb = CircuitBreaker(2, 1_000L)
        assertTrue(cb.canExecute(0L))
        cb.recordFailure(0L)
        assertTrue(cb.canExecute(100L))
        cb.recordFailure(100L)
        assertTrue(cb.isOpen(200L))
        assertFalse(cb.canExecute(200L))
        assertTrue(cb.canExecute(1_200L))
        cb.recordSuccess()
        assertTrue(cb.canExecute(1_300L))
    }

    @Test
    fun `config separates queue and execution timeouts`() {
        val cfg = GatekeeperConfig(queueWaitTimeoutMs = 2_000L, npuExecutionTimeoutMs = 15_000L)
        assertFalse(cfg.queueWaitTimeoutMs == cfg.npuExecutionTimeoutMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects bad retries`() {
        GatekeeperConfig(maxRetries = 9)
    }
}
