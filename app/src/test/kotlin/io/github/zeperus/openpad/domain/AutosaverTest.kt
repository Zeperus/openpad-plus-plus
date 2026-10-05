package io.github.zeperus.openpad.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutosaverTest {
    @Test fun `saves once after the idle delay`() = runTest {
        var saves = 0
        val s = Autosaver(backgroundScope, 800, 5_000, { currentTime }) { saves++ }
        s.notifyChanged()
        advanceTimeBy(799); runCurrent()
        assertEquals(0, saves)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, saves)
    }

    @Test fun `no changes means no saves`() = runTest {
        var saves = 0
        Autosaver(backgroundScope, 800, 5_000, { currentTime }) { saves++ }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, saves)
    }

    @Test fun `a burst of changes results in a single save`() = runTest {
        var saves = 0
        val s = Autosaver(backgroundScope, 800, 5_000, { currentTime }) { saves++ }
        repeat(5) { s.notifyChanged(); advanceTimeBy(300); runCurrent() }
        assertEquals(0, saves)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, saves)
    }

    @Test fun `continuous typing is still saved regularly`() = runTest {
        var saves = 0
        val s = Autosaver(backgroundScope, 800, 5_000, { currentTime }) { saves++ }
        repeat(40) { s.notifyChanged(); advanceTimeBy(500); runCurrent() } // 20 s of typing
        assertEquals(true, saves in 3..4)
    }

    @Test fun `flush saves immediately`() = runTest {
        var saves = 0
        val s = Autosaver(backgroundScope, 800, 5_000, { currentTime }) { saves++ }
        s.notifyChanged()
        s.flush()
        assertEquals(1, saves)
    }

    @Test fun `failures are reported and the next change retries`() = runTest {
        var attempts = 0
        val errors = mutableListOf<Throwable>()
        val s = Autosaver(backgroundScope, 800, 5_000, { currentTime }, onError = { errors += it }) {
            attempts++
            if (attempts == 1) throw java.io.IOException("disk full")
        }
        s.notifyChanged()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, errors.size)
        s.notifyChanged()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, errors.size)
    }

    @Test fun `close stops the loop`() = runTest {
        var saves = 0
        val s = Autosaver(backgroundScope, 800, 5_000, { currentTime }) { saves++ }
        s.close()
        s.notifyChanged()
        delay(5_000); runCurrent()
        assertEquals(0, saves)
    }
}
