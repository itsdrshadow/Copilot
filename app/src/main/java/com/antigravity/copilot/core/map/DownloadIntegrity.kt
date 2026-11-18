package com.antigravity.copilot.core.map

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Downloaded map integrity (audit J2): a power cut mid-download must never leave a truncated
 * archive in place. Checked before the atomic move: byte count vs Content-Length, PMTiles header
 * and every section (directories, metadata, tile data) inside the file, and SHA-256 when known.
 */
object DownloadIntegrity {

    /** null = OK, else the reason. */
    fun sizeProblem(downloadedBytes: Long, expectedBytes: Long): String? = when {
        downloadedBytes <= 0L -> "empty download"
        expectedBytes > 0L && downloadedBytes != expectedBytes -> "incomplete download ($downloadedBytes of $expectedBytes bytes)"
        else -> null
    }

    /** All PMTiles v3 sections must lie inside a file of [fileLength] bytes. */
    fun pmtilesSectionsProblem(header: ByteArray, fileLength: Long): String? {
        if (header.size < 127) return "file too small"
        if (String(header, 0, 7, Charsets.US_ASCII) != "PMTiles" || header[7].toInt() != 3) return "not a PMTiles v3 archive"
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        for (off in intArrayOf(8, 24, 40, 56)) {
            val start = b.getLong(off)
            val len = b.getLong(off + 8)
            if (start < 0 || len < 0 || start + len > fileLength) return "truncated archive (section at $start+$len > $fileLength)"
        }
        return null
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** First hex token of a `.sha256` sidecar ("<hash>  <name>"). */
    fun parseSha256Sidecar(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    fun verify(file: File, expectedBytes: Long, expectedSha256: String?): String? {
        sizeProblem(file.length(), expectedBytes)?.let { return it }
        val header = ByteArray(127)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < header.size) return "file too small"
            raf.readFully(header)
        }
        pmtilesSectionsProblem(header, file.length())?.let { return it }
        if (expectedSha256 != null && !sha256(file).equals(expectedSha256, ignoreCase = true)) return "checksum mismatch"
        return null
    }
}
