package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TailscaleStateLeaseTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun duplicateReservationDoesNotOpenAndCloseAnotherDescriptor() {
        val directory = temporary.newFolder()
        TailscaleStateLease.acquire(directory, listOf(1)).use {
            assertNotNull(runCatching { TailscaleStateLease.acquire(directory, listOf(1)) }.exceptionOrNull())
            // A second descriptor closed by a failed acquisition would drop the first POSIX lock.
            assertEquals("busy", probe(directory, 1))
        }
        assertEquals("acquired", probe(directory, 1))
        assertTrue(File(directory, "1.lock").isFile)
    }

    @Test
    fun partialAcquisitionIsReleasedAndOtherNodesRemainIndependent() {
        val directory = temporary.newFolder()
        TailscaleStateLease.acquire(directory, listOf(2)).use {
            assertNotNull(runCatching { TailscaleStateLease.acquire(directory, listOf(1, 2, 3)) }.exceptionOrNull())
            TailscaleStateLease.acquire(directory, listOf(1, 3)).use {
                assertEquals("busy", probe(directory, 1))
                assertEquals("busy", probe(directory, 2))
            }
        }
        TailscaleStateLease.acquire(directory, listOf(3, 2, 1, 1)).use { }
    }

    @Test
    fun separateProcessOwnerPreventsAcquisitionUntilItsClose() {
        val directory = temporary.newFolder()
        val process = process(directory, 7, hold = true)
        val reader = Executors.newSingleThreadExecutor()
        try {
            val ready = reader.submit<String> { process.inputStream.bufferedReader().readLine() }
            assertEquals("acquired", ready.get(10, TimeUnit.SECONDS))
            assertNotNull(runCatching { TailscaleStateLease.acquire(directory, listOf(7)) }.exceptionOrNull())
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            TailscaleStateLease.acquire(directory, listOf(7)).use { }
        } finally {
            process.destroyForcibly()
            reader.shutdownNow()
        }
    }

    private fun probe(directory: File, id: Long): String {
        val process = process(directory, id)
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return process.inputStream.bufferedReader().readText().trim()
        } finally {
            process.destroyForcibly()
        }
    }

    private fun process(directory: File, id: Long, hold: Boolean = false): Process {
        val fixture = TailscaleLockProcess::class.java
        return ProcessBuilder(
            listOf(
                File(System.getProperty("java.home"), "bin/java").path,
                "-cp",
                File(requireNotNull(requireNotNull(fixture.protectionDomain).codeSource).location.toURI()).path,
                fixture.name,
                File(directory, "$id.lock").path,
            ) + if (hold) listOf("hold") else emptyList(),
        ).redirectErrorStream(true).start()
    }
}
