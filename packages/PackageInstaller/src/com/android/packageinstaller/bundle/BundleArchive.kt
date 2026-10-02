/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.bundle

import android.content.Context
import android.os.ParcelFileDescriptor
import com.android.packageinstaller.R
import com.android.packageinstaller.common.TemporaryFileManager
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal class BundleException(val errorRes: Int) : Exception()

internal class BundleScratch private constructor(val descriptor: ParcelFileDescriptor) : Closeable {
    val file: File get() = File("/proc/self/fd/${descriptor.fd}")

    fun output(): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(
        ParcelFileDescriptor.dup(descriptor.fileDescriptor))

    override fun close() = descriptor.close()

    companion object {
        fun create(context: Context): BundleScratch {
            val file = TemporaryFileManager.getStagedFile(context)
            try {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
                // Unlink before writing so process death cannot leave bundle contents behind
                if (!file.delete()) {
                    descriptor.close()
                    throw BundleException(R.string.bundle_error_io)
                }
                return BundleScratch(descriptor)
            } finally {
                file.delete()
            }
        }
    }
}

internal class BundleArchive private constructor(
    private val scratch: BundleScratch,
    private val zip: ZipFile,
    private val checkCancelled: () -> Unit,
) : Closeable {
    val entries = linkedMapOf<String, ZipEntry>()
    val apks: List<ZipEntry>
    val hasObb: Boolean
    enum class Format { APKS, XAPK, APKM, BUNDLETOOL }
    val format: Format

    init {
        var total = 0L
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            checkCancelled()
            val entry = iterator.nextElement()
            validateName(entry.name, entry.isDirectory)
            if (entries.put(entry.name, entry) != null) invalid()
            if (entries.size > MAX_ENTRIES || entry.size < 0 || entry.size > MAX_EXPANDED - total) {
                throw BundleException(R.string.bundle_error_limit)
            }
            total += entry.size
        }
        apks = entries.values.filter { !it.isDirectory && it.name.endsWith(".apk", true) }
        if (apks.size > MAX_APKS) throw BundleException(R.string.bundle_error_limit)
        if (apks.isEmpty()) throw BundleException(R.string.bundle_error_no_base)
        if (apks.any { it.size > MAX_ARCHIVE }) throw BundleException(R.string.bundle_error_limit)
        hasObb = entries.keys.any { it.endsWith(".obb", true) }
        format = when {
            entries.containsKey("toc.pb") -> Format.BUNDLETOOL
            entries.containsKey("manifest.json") -> Format.XAPK
            entries.containsKey("info.json") -> Format.APKM
            else -> Format.APKS
        }
    }

    fun copy(entry: ZipEntry, out: OutputStream, progress: (Long) -> Unit = {}) {
        zip.getInputStream(entry).use { input ->
            val crc = CRC32()
            val count = transfer(input, out, entry.size, checkCancelled) { bytes, buffer, length ->
                crc.update(buffer, 0, length)
                progress(bytes)
            }
            if (count != entry.size || crc.value != entry.crc) invalid()
        }
    }

    fun <T> readToc(read: (InputStream, Long) -> T): T {
        val entry = entries.getValue("toc.pb")
        if (entry.size > MAX_TOC) throw BundleException(R.string.bundle_error_limit)
        return CheckedInputStream(zip.getInputStream(entry), CRC32()).use { input ->
            val result = read(input, entry.size)
            if (input.read() != -1 || input.checksum.value != entry.crc) invalid()
            result
        }
    }

    override fun close() {
        try {
            zip.close()
        } finally {
            scratch.close()
        }
    }

    companion object {
        const val MAX_ENTRIES = 4096
        const val MAX_APKS = 512
        const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024
        const val MAX_EXPANDED = 4L * 1024 * 1024 * 1024
        const val MAX_TOC = 4L * 1024 * 1024
        private const val MAX_DIRECTORY = 8L * 1024 * 1024

        fun open(context: Context, input: InputStream, checkCancelled: () -> Unit,
            progress: (Long) -> Unit): BundleArchive {
            val scratch = BundleScratch.create(context)
            var zip: ZipFile? = null
            try {
                scratch.output().use { out ->
                    transfer(input, out, MAX_ARCHIVE, checkCancelled) { bytes, _, _ -> progress(bytes) }
                }
                checkDirectory(scratch.file, checkCancelled)
                zip = ZipFile(scratch.file)
                return BundleArchive(scratch, zip, checkCancelled)
            } catch (e: Exception) {
                try {
                    zip?.close()
                } finally {
                    scratch.close()
                }
                throw e
            }
        }

        private fun transfer(input: InputStream, out: OutputStream, limit: Long,
            checkCancelled: () -> Unit, progress: (Long, ByteArray, Int) -> Unit): Long {
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) return total
                if (count == 0) continue
                if (count > limit - total) throw BundleException(R.string.bundle_error_limit)
                out.write(buffer, 0, count)
                total += count
                progress(total, buffer, count)
            }
        }

        fun validateName(name: String, directory: Boolean = false) {
            val path = if (directory) name.removeSuffix("/") else name
            if (path.isEmpty() || name.length > 1024 || path.startsWith('/') ||
                path.any { it == '\\' || it == ':' || it.code < 32 } ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid()
        }

        private fun invalid(): Nothing = throw BundleException(R.string.bundle_error_invalid)

        private fun checkDirectory(file: File, checkCancelled: () -> Unit) {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                if (length < 22) invalid()
                val tail = ByteArray(minOf(length, 65557).toInt())
                input.seek(length - tail.size)
                input.readFully(tail)
                fun number(offset: Int, bytes: Int): Long {
                    var result = 0L
                    for (i in 0 until bytes) {
                        result = result or ((tail[offset + i].toLong() and 255) shl (8 * i))
                    }
                    return result
                }
                val end = (tail.size - 22 downTo 0).firstOrNull {
                    number(it, 4) == 0x06054b50L && it + 22 + number(it + 20, 2) == tail.size.toLong()
                } ?: invalid()
                val count = number(end + 10, 2)
                val size = number(end + 12, 4)
                val offset = number(end + 16, 4)
                if (number(end + 4, 2) != 0L || number(end + 6, 2) != 0L ||
                    number(end + 8, 2) != count || offset + size != length - tail.size + end) invalid()
                if (count > MAX_ENTRIES || size > MAX_DIRECTORY) {
                    throw BundleException(R.string.bundle_error_limit)
                }
                input.seek(offset)
                repeat(count.toInt()) {
                    checkCancelled()
                    val header = ByteArray(46)
                    input.readFully(header)
                    fun word(index: Int): Int = (header[index].toInt() and 255) or
                        ((header[index + 1].toInt() and 255) shl 8)
                    if (word(0) != 0x4b50 || word(2) != 0x0201 || word(34) != 0) invalid()
                    if (word(8) and 0x41 != 0) throw BundleException(R.string.bundle_error_encrypted)
                    if (word(10) != 0 && word(10) != 8) {
                        throw BundleException(R.string.bundle_error_unsupported)
                    }
                    if (word(28) > 4096) throw BundleException(R.string.bundle_error_limit)
                    val next = input.filePointer + word(28) + word(30) + word(32)
                    if (next > offset + size) invalid()
                    input.seek(next)
                }
                if (input.filePointer != offset + size) invalid()
            }
        }
    }
}
