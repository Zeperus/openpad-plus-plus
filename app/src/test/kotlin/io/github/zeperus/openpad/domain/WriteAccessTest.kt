package io.github.zeperus.openpad.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The write-access decision from the individually unreliable signals Android gives (grant, capability flag, probe). */
class WriteAccessTest {
    private fun decide(
        requiresGrant: Boolean = true,
        grant: Boolean,
        flag: Boolean?,
        probe: WriteProbe = WriteProbe.Opened,
        probed: (() -> Unit)? = null,
    ) = runBlocking {
        WriteAccessPolicy.evaluate(requiresGrant, grant, flag) { probed?.invoke(); probe }
    }

    private val writable = WriteAccess.Writable
    private fun readOnly(r: ReadOnlyReason) = WriteAccess.ReadOnly(r)

    @Test fun `a document with a write grant and the provider flag is writable without probing`() {
        var probes = 0
        assertEquals(writable, decide(grant = true, flag = true, probed = { probes++ }))
        assertEquals(0, probes)
    }

    @Test fun `a document picked or opened with read access only is read-only because no write was granted`() {
        var probes = 0
        assertEquals(readOnly(ReadOnlyReason.NoWriteGrant), decide(grant = false, flag = true, probed = { probes++ }))
        assertEquals("nothing is opened or written to find that out", 0, probes)
    }

    @Test fun `a revoked permission makes a previously writable document read-only`() {
        assertEquals(writable, decide(grant = true, flag = true))
        assertEquals(readOnly(ReadOnlyReason.NoWriteGrant), decide(grant = false, flag = true))
    }

    @Test fun `a provider without capability flags is judged by trying to open it for writing`() {
        assertEquals(writable, decide(grant = true, flag = null, probe = WriteProbe.Opened))
        assertEquals(readOnly(ReadOnlyReason.ProviderRefuses), decide(grant = true, flag = null, probe = WriteProbe.Refused))
    }

    @Test fun `a flag that says no does not override a probe that works`() {
        assertEquals(writable, decide(grant = true, flag = false, probe = WriteProbe.Opened))
        assertEquals(readOnly(ReadOnlyReason.ProviderRefuses), decide(grant = true, flag = false, probe = WriteProbe.Refused))
    }

    @Test fun `a probe that is denied means no write permission, one that cannot tell means unavailable`() {
        assertEquals(readOnly(ReadOnlyReason.NoWriteGrant), decide(grant = true, flag = null, probe = WriteProbe.Denied))
        assertEquals(readOnly(ReadOnlyReason.Unavailable), decide(grant = true, flag = null, probe = WriteProbe.Unknown))
    }

    @Test fun `other content uris need no grant record - the probe decides`() {
        assertEquals(writable, decide(requiresGrant = false, grant = false, flag = null, probe = WriteProbe.Opened))
        assertEquals(readOnly(ReadOnlyReason.NoWriteGrant), decide(requiresGrant = false, grant = false, flag = null, probe = WriteProbe.Denied))
    }
}
