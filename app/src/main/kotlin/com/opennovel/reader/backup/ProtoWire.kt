package com.opennovel.reader.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Minimal protobuf wire-format reader and writer.
 *
 * Hand-rolled instead of kotlinx-serialization-protobuf for two reasons: the
 * Manatan container uses a custom recursive value encoding that has no static
 * schema, and a 77 MB table has to be walked as a stream rather than decoded
 * into one object graph. Using one small codec for both formats keeps the
 * behaviour on malformed input identical.
 */
internal class ProtoFormatException(message: String) : IOException(message)

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LEN = 2
internal const val WIRE_FIXED32 = 5

internal fun zigzagDecode(v: Long): Long = (v ushr 1) xor -(v and 1L)

/** Reads protobuf fields from a byte range. Throws [ProtoFormatException] on malformed input. */
internal class ProtoReader(
    private val buf: ByteArray,
    private var pos: Int = 0,
    private val end: Int = buf.size,
) {
    var field: Int = 0
        private set
    var wireType: Int = 0
        private set

    /** Advances to the next field; false at the end of the range. */
    fun next(): Boolean {
        if (pos >= end) return false
        val tag = readVarint()
        field = (tag ushr 3).toInt()
        wireType = (tag and 7L).toInt()
        if (field <= 0) throw ProtoFormatException("invalid field number $field")
        return true
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw ProtoFormatException("truncated varint")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw ProtoFormatException("varint too long")
        }
    }

    fun readInt(): Int = readVarint().toInt()
    fun readBool(): Boolean = readVarint() != 0L

    fun readFixed32(): Int {
        if (pos + 4 > end) throw ProtoFormatException("truncated fixed32")
        var r = 0
        for (i in 0 until 4) r = r or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return r
    }

    fun readFixed64(): Long {
        if (pos + 8 > end) throw ProtoFormatException("truncated fixed64")
        var r = 0L
        for (i in 0 until 8) r = r or ((buf[pos + i].toLong() and 0xFFL) shl (8 * i))
        pos += 8
        return r
    }

    fun readFloat(): Float = Float.fromBits(readFixed32())
    fun readDouble(): Double = Double.fromBits(readFixed64())

    private fun readLength(): Int {
        val len = readVarint()
        if (len < 0 || len > end - pos) throw ProtoFormatException("length $len exceeds message")
        return len.toInt()
    }

    fun readBytes(): ByteArray {
        val len = readLength()
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readString(): String {
        val len = readLength()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    /** Sub-reader over a length-delimited field's payload. */
    fun readMessage(): ProtoReader {
        val len = readLength()
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    /** Reads a repeated varint element that may be packed (LEN) or unpacked. */
    fun readVarintsInto(out: MutableList<Long>) {
        if (wireType == WIRE_LEN) {
            val sub = readMessage()
            while (sub.pos < sub.end) out.add(sub.readVarint())
        } else {
            out.add(readVarint())
        }
    }

    fun skip() {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> { if (pos + 8 > end) throw ProtoFormatException("truncated"); pos += 8 }
            WIRE_LEN -> { val n = readLength(); pos += n }
            WIRE_FIXED32 -> { if (pos + 4 > end) throw ProtoFormatException("truncated"); pos += 4 }
            else -> throw ProtoFormatException("unsupported wire type $wireType")
        }
    }
}

/** Writes protobuf fields. Zero/empty defaults are the caller's choice to omit. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    fun varint(field: Int, value: Long) { tag(field, WIRE_VARINT); rawVarint(value) }
    fun int(field: Int, value: Int) = varint(field, value.toLong())
    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1L else 0L)

    fun float(field: Int, value: Float) {
        tag(field, WIRE_FIXED32)
        val b = value.toRawBits()
        for (i in 0 until 4) out.write((b ushr (8 * i)) and 0xFF)
    }

    fun double(field: Int, value: Double) {
        tag(field, WIRE_FIXED64)
        val b = value.toRawBits()
        for (i in 0 until 8) out.write(((b ushr (8 * i)) and 0xFF).toInt())
    }

    fun bytes(field: Int, value: ByteArray) {
        tag(field, WIRE_LEN); rawVarint(value.size.toLong()); out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit) {
        bytes(field, ProtoWriter().apply(build).toByteArray())
    }
}

/**
 * Forward-only protobuf reader over a stream, for inputs too large to buffer.
 *
 * Callers track nesting with absolute end offsets ([position] based) and pull
 * small leaf messages into memory with [readBytes] to decode them with
 * [ProtoReader]; large payloads are stepped over with [skipBytes].
 */
internal class ProtoStream(private val input: InputStream) {
    var position: Long = 0
        private set

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw ProtoFormatException("unexpected end of stream at $position")
        position++
        return b
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw ProtoFormatException("varint too long")
        }
    }

    /**
     * Next tag as (field shl 3 | wire), or -1 once [position] reaches [end].
     * With [eofOk], a clean end of stream before the first tag byte also
     * returns -1 (for the outermost message, whose length is unknown).
     */
    fun readTag(end: Long, eofOk: Boolean = false): Long {
        if (position >= end) return -1
        if (eofOk) {
            val first = input.read()
            if (first < 0) return -1
            position++
            var tag = (first and 0x7F).toLong()
            var shift = 7
            var b = first
            while (b and 0x80 != 0) {
                b = readByte()
                tag = tag or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (shift > 63) throw ProtoFormatException("varint too long")
            }
            if ((tag ushr 3) <= 0) throw ProtoFormatException("invalid field number")
            return tag
        }
        val tag = readVarint()
        if ((tag ushr 3) <= 0) throw ProtoFormatException("invalid field number")
        return tag
    }

    fun readLength(end: Long): Int {
        val len = readVarint()
        if (len < 0 || len > end - position || len > Int.MAX_VALUE) {
            throw ProtoFormatException("length $len exceeds enclosing message")
        }
        return len.toInt()
    }

    /** Reads a length prefix and returns the absolute offset where that payload ends. */
    fun readLengthEnd(end: Long): Long {
        val len = readLength(end)
        return position + len
    }

    fun readBytes(len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw ProtoFormatException("unexpected end of stream")
            off += n
        }
        position += len
        return buf
    }

    fun skipBytes(n: Long) {
        var left = n
        val scratch = ByteArray(8192)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                val r = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (r < 0) throw ProtoFormatException("unexpected end of stream")
                left -= r
            }
        }
        position += n
    }

    /** Steps over the payload of a field whose tag was just read. */
    fun skipField(wire: Int, end: Long) {
        when (wire) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> skipBytes(8)
            WIRE_FIXED32 -> skipBytes(4)
            WIRE_LEN -> skipBytes(readLength(end).toLong())
            else -> throw ProtoFormatException("unsupported wire type $wire")
        }
    }
}
