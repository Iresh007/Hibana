package com.opennovel.reader.backup

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reader for the `.manatanbk` container, reverse engineered from a real export
 * (no public specification exists).
 *
 * After optional gzip, the file is a protobuf message:
 *
 *  - 1: format version (varint; 10 in the sample), 2: label, 3: timestamp millis
 *  - 5 (repeated): `{1: key string, 2: JSON string}` — the novel library
 *  - 7: `{ 9: { 1 (repeated): { 1: table name, 2: table Value } } }` — database tables
 *
 * A table Value is a recursive tagged value: field 1 = null marker, 2 = bool (unverified), 3 = sint64
 * (zigzag), 5 = double, 6 = string, 8 = list (repeated field 1 = Value),
 * 9 = map (repeated field 1 = `{1: column name, 2: Value}`). A table is a list
 * of maps, one per row.
 *
 * Tables are streamed one row at a time: a real export inflates to ~77 MB, of
 * which ~64 MB is the `chapters` table, so nothing larger than a single row is
 * ever held. Tables without a registered handler are stepped over without
 * being decoded.
 */
internal object ManatanContainer {

    fun openMaybeGzip(raw: InputStream): InputStream {
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1F && b1 == 0x8B) {
            BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024)
        } else {
            buffered
        }
    }

    /**
     * Walks the file once. [onKv] receives each novel-store record; [tables]
     * maps a table name to a row handler. Rows are column-name to decoded value
     * (null, Long, Double, String, List or Map).
     */
    fun scan(
        raw: InputStream,
        onKv: ((key: String, json: String) -> Unit)?,
        tables: Map<String, (Map<String, Any?>) -> Unit>,
    ) {
        val s = ProtoStream(openMaybeGzip(raw))
        val end = Long.MAX_VALUE
        while (true) {
            val tag = s.readTag(end, eofOk = true)
            if (tag < 0) return
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 5 && wire == WIRE_LEN && onKv != null -> {
                    val len = s.readLength(end)
                    val r = ProtoReader(s.readBytes(len))
                    var key: String? = null
                    var value: String? = null
                    while (r.next()) {
                        if (r.wireType != WIRE_LEN) { r.skip(); continue }
                        when (r.field) {
                            1 -> key = r.readString()
                            2 -> value = r.readString()
                            else -> r.skip()
                        }
                    }
                    if (key != null && value != null) onKv(key, value)
                }
                field == 7 && wire == WIRE_LEN && tables.isNotEmpty() -> {
                    scanTableContainer(s, s.readLengthEnd(end), tables)
                }
                else -> s.skipField(wire, end)
            }
        }
    }

    private fun scanTableContainer(s: ProtoStream, end: Long, tables: Map<String, (Map<String, Any?>) -> Unit>) {
        while (true) {
            val tag = s.readTag(end)
            if (tag < 0) return
            val wire = (tag and 7L).toInt()
            if ((tag ushr 3).toInt() == 9 && wire == WIRE_LEN) {
                val listEnd = s.readLengthEnd(end)
                while (true) {
                    val t2 = s.readTag(listEnd)
                    if (t2 < 0) break
                    val w2 = (t2 and 7L).toInt()
                    if ((t2 ushr 3).toInt() == 1 && w2 == WIRE_LEN) {
                        val entryEnd = s.readLengthEnd(listEnd)
                        scanTableEntry(s, entryEnd, tables)
                    } else {
                        s.skipField(w2, listEnd)
                    }
                }
            } else {
                s.skipField(wire, end)
            }
        }
    }

    private fun scanTableEntry(s: ProtoStream, end: Long, tables: Map<String, (Map<String, Any?>) -> Unit>) {
        var name: String? = null
        while (true) {
            val tag = s.readTag(end)
            if (tag < 0) return
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 1 && wire == WIRE_LEN) {
                name = String(s.readBytes(s.readLength(end)), Charsets.UTF_8)
            } else if (field == 2 && wire == WIRE_LEN) {
                val len = s.readLength(end)
                val handler = name?.let { tables[it] }
                // `len` was read first, so position is already past the length prefix.
                if (handler != null) streamRows(s, s.position + len, handler) else s.skipBytes(len.toLong())
            } else {
                s.skipField(wire, end)
            }
        }
    }

    private fun streamRows(s: ProtoStream, end: Long, handler: (Map<String, Any?>) -> Unit) {
        while (true) {
            val tag = s.readTag(end)
            if (tag < 0) return
            val wire = (tag and 7L).toInt()
            if ((tag ushr 3).toInt() == 8 && wire == WIRE_LEN) {
                val listEnd = s.readLengthEnd(end)
                while (true) {
                    val t2 = s.readTag(listEnd)
                    if (t2 < 0) break
                    val w2 = (t2 and 7L).toInt()
                    if ((t2 ushr 3).toInt() == 1 && w2 == WIRE_LEN) {
                        val row = decodeValue(ProtoReader(s.readBytes(s.readLength(listEnd))))
                        @Suppress("UNCHECKED_CAST")
                        if (row is Map<*, *>) handler(row as Map<String, Any?>)
                    } else {
                        s.skipField(w2, listEnd)
                    }
                }
            } else {
                s.skipField(wire, end)
            }
        }
    }

    /** Decodes one tagged Value (see class docs). */
    fun decodeValue(r: ProtoReader): Any? {
        var out: Any? = null
        while (r.next()) {
            when {
                r.field == 1 && r.wireType == WIRE_VARINT -> { r.skip(); out = null }
                // Field 2 only appears in tables we do not import (sharedPreferences,
                // immersionArchive); read as a boolean, unverified.
                r.field == 2 && r.wireType == WIRE_VARINT -> out = r.readVarint() != 0L
                r.field == 3 && r.wireType == WIRE_VARINT -> out = zigzagDecode(r.readVarint())
                r.field == 5 && r.wireType == WIRE_FIXED64 -> out = r.readDouble()
                r.field == 6 && r.wireType == WIRE_LEN -> out = r.readString()
                r.field == 8 && r.wireType == WIRE_LEN -> {
                    val sub = r.readMessage()
                    val list = ArrayList<Any?>()
                    while (sub.next()) {
                        if (sub.field == 1 && sub.wireType == WIRE_LEN) list.add(decodeValue(sub.readMessage())) else sub.skip()
                    }
                    out = list
                }
                r.field == 9 && r.wireType == WIRE_LEN -> {
                    val sub = r.readMessage()
                    val map = LinkedHashMap<String, Any?>()
                    while (sub.next()) {
                        if (sub.field != 1 || sub.wireType != WIRE_LEN) { sub.skip(); continue }
                        val entry = sub.readMessage()
                        var key: String? = null
                        var value: Any? = null
                        while (entry.next()) {
                            when {
                                entry.field == 1 && entry.wireType == WIRE_LEN -> key = entry.readString()
                                entry.field == 2 && entry.wireType == WIRE_LEN -> value = decodeValue(entry.readMessage())
                                else -> entry.skip()
                            }
                        }
                        if (key != null) map[key] = value
                    }
                    out = map
                }
                else -> r.skip()
            }
        }
        return out
    }
}
