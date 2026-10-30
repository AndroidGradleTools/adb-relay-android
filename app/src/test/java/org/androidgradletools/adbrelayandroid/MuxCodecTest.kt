package org.androidgradletools.adbrelayandroid

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxCodecTest {

    /** A header that declares [declaredLength], followed by [trailing] bytes. */
    private fun header(declaredLength: Int, trailing: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(MuxCodec.HEADER_SIZE + trailing).order(ByteOrder.BIG_ENDIAN)
        bb.put(byteArrayOf(0x41, 0x44, 0x42, 0x4d))
        bb.put(1.toByte())
        bb.put(MuxCodec.TYPE_DATA.toByte())
        bb.putShort(0)
        bb.putInt(7)
        bb.putInt(declaredLength)
        return bb.array()
    }

    @Test
    fun roundTripsFrames() {
        val a = MuxCodec.encode(MuxCodec.TYPE_OPEN, 3)
        val b = MuxCodec.encode(MuxCodec.TYPE_DATA, 3, byteArrayOf(1, 2, 3))
        val buf = a + b
        val (consumed, frames) = MuxCodec.parse(buf, 0, buf.size)
        assertEquals(buf.size, consumed)
        assertEquals(2, frames.size)
        assertEquals(MuxCodec.TYPE_OPEN, frames[0].type)
        assertArrayEquals(byteArrayOf(1, 2, 3), frames[1].payload)
    }

    @Test
    fun waitsForAnIncompletePayload() {
        val full = MuxCodec.encode(MuxCodec.TYPE_DATA, 3, ByteArray(10))
        val (consumed, frames) = MuxCodec.parse(full, 0, full.size - 1)
        assertEquals(0, consumed)
        assertTrue(frames.isEmpty())
    }

    // -16 made total 0: pos never advanced, and parse added empty frames until
    // OutOfMemoryError.
    @Test(timeout = 5_000)
    fun rejectsLengthThatWouldStallTheCursor() {
        val buf = header(-HEADER)
        assertThrows(IllegalArgumentException::class.java) { MuxCodec.parse(buf, 0, buf.size) }
    }

    // -32 after a 16-byte OPEN frame made total -16: pos jumped back onto the OPEN header,
    // re-read it, reached the bad header again, and looped until OutOfMemoryError.
    @Test(timeout = 5_000)
    fun rejectsLengthThatWouldMoveTheCursorBackwards() {
        val buf = MuxCodec.encode(MuxCodec.TYPE_OPEN, 3) + header(-2 * HEADER)
        assertThrows(IllegalArgumentException::class.java) { MuxCodec.parse(buf, 0, buf.size) }
    }

    // HEADER_SIZE + Int.MAX_VALUE wrapped negative, and copyOfRange's to - from wrapped back to
    // a ~2 GiB allocation: OutOfMemoryError, which the service's catch (e: Exception) misses.
    @Test(timeout = 5_000)
    fun rejectsLengthThatOverflowsTheFrameTotal() {
        val buf = header(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { MuxCodec.parse(buf, 0, buf.size) }
    }

    private companion object {
        const val HEADER = MuxCodec.HEADER_SIZE
    }
}
