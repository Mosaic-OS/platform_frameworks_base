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

import android.os.Build
import com.android.packageinstaller.R
import java.io.BufferedInputStream
import java.io.InputStream

internal data class BundleTarget(
    val abi: Dimension? = null,
    val density: Dimension? = null,
    val sdk: Dimension? = null,
    val languages: Set<String>? = null,
    val unsupported: Boolean = false,
) {
    data class Dimension(val values: Set<Int>, val alternatives: Set<Int>)

    fun matches(dpi: Int): Boolean = !unsupported && matchesSdk() &&
        matchesAbi() && (density?.let { dimension ->
            val best = bestDensity(dimension.values + dimension.alternatives, dpi)
            best in dimension.values || (best == null && dimension.values.isEmpty())
        } ?: true)

    fun matchesSdk(): Boolean = sdk?.let { dimension ->
        val best = (dimension.values + dimension.alternatives + 0)
            .filter { it <= Build.VERSION.SDK_INT }.maxOrNull()
        best in dimension.values || (best == 0 && dimension.values.isEmpty())
    } ?: true

    fun matchesAbi(): Boolean = abi?.let { dimension ->
        val best = Build.SUPPORTED_ABIS.map { ABI_NAMES.indexOf(it) }
            .firstOrNull { it > 0 && it in dimension.values + dimension.alternatives }
        best in dimension.values || (best == null && dimension.values.isEmpty())
    } ?: true

    companion object {
        val ABI_NAMES = listOf("", "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64",
            "mips", "mips64", "riscv64")

        fun bestDensity(values: Set<Int>, dpi: Int): Int? =
            values.filter { it >= dpi }.minOrNull() ?: values.maxOrNull()
    }
}

internal object BundleToc {
    private data class Apk(val path: String, val kind: Int, val target: BundleTarget)
    private data class Variant(val number: Int, val target: BundleTarget, val apks: List<Apk>)

    fun select(input: InputStream, length: Long, dpi: Int): Map<String, BundleTarget> {
        val variants = mutableListOf<Variant>()
        val buffered = BufferedInputStream(input)
        Wire(buffered, length).fields { field ->
            if (field == 1) {
                if (variants.size >= 128) throw BundleException(R.string.bundle_error_limit)
                variants += message { readVariant(it) }
            } else skip()
        }
        if (buffered.read() != -1) invalid()
        val variant = variants.filter { it.target.matches(dpi) &&
            it.apks.any { apk -> apk.kind == 3 || apk.kind == 4 } }.maxByOrNull { it.number }
            ?: throw BundleException(R.string.bundle_error_unsupported)
        val splits = variant.apks.filter { it.kind == 3 && it.target.matchesSdk() }
        val apks = if (splits.isNotEmpty()) splits else variant.apks.filter {
            it.kind == 4 && it.target.matches(dpi)
        }
        if (apks.isEmpty()) throw BundleException(R.string.bundle_error_incompatible)
        val result = linkedMapOf<String, BundleTarget>()
        for (apk in apks) {
            BundleArchive.validateName(apk.path)
            if (!apk.path.endsWith(".apk", true) || result.put(apk.path, apk.target) != null) invalid()
        }
        return result
    }

    private fun readVariant(wire: Wire): Variant {
        var number = 0
        var target = BundleTarget()
        val apks = mutableListOf<Apk>()
        wire.fields { field ->
            when (field) {
                1 -> target = message { readTarget(it, true) }
                2 -> message { set ->
                    set.fields { item ->
                        if (item == 2) {
                            if (apks.size >= BundleArchive.MAX_APKS) {
                                throw BundleException(R.string.bundle_error_limit)
                            }
                            apks += message { readApk(it) }
                        } else skip()
                    }
                }
                3 -> number = integer()
                else -> skip()
            }
        }
        return Variant(number, target, apks)
    }

    private fun readApk(wire: Wire): Apk {
        var path = ""
        var kind = 0
        var target = BundleTarget()
        wire.fields { field ->
            when (field) {
                1 -> target = message { readTarget(it, false) }
                2 -> path = string()
                in 3..9 -> {
                    if (kind != 0) invalid()
                    kind = field
                    skip()
                }
                else -> skip()
            }
        }
        return Apk(path, kind, target)
    }

    private fun readTarget(wire: Wire, variant: Boolean): BundleTarget {
        var abi: BundleTarget.Dimension? = null
        var density: BundleTarget.Dimension? = null
        var sdk: BundleTarget.Dimension? = null
        var languages: Set<String>? = null
        var unsupported = false
        wire.fields { field ->
            when {
                field == (if (variant) 1 else 5) -> sdk = message { readDimension(it, 0) }
                field == (if (variant) 2 else 1) -> abi = message { readDimension(it, 1) }
                field == (if (variant) 3 else 4) -> density = message { readDimension(it, 2) }
                !variant && field == 3 -> languages = message { language ->
                    val values = mutableSetOf<String>()
                    language.fields { if (it == 1) values += string() else skip() }
                    values
                }
                variant && field == 6 -> message { runtime ->
                    runtime.fields { if (it == 1) unsupported = (integer() != 0) || unsupported else skip() }
                }
                else -> {
                    unsupported = true
                    skip()
                }
            }
        }
        return BundleTarget(abi, density, sdk, languages, unsupported)
    }

    private fun readDimension(wire: Wire, kind: Int): BundleTarget.Dimension {
        val values = mutableSetOf<Int>()
        val alternatives = mutableSetOf<Int>()
        wire.fields { field ->
            if (field == 1 || field == 2) {
                val value = message { item ->
                    var value = 0
                    item.fields { attribute ->
                        when {
                            kind == 0 && attribute == 1 -> message { wrapper ->
                                wrapper.fields { if (it == 1) value = integer() else skip() }
                            }
                            kind == 1 && attribute == 1 -> value = integer()
                            kind == 2 && attribute == 1 -> {
                                value = listOf(0, 0, 120, 160, 213, 240, 320, 480, 640)
                                    .getOrNull(integer()) ?: invalid()
                            }
                            kind == 2 && attribute == 2 -> value = integer()
                            else -> skip()
                        }
                    }
                    value
                }
                if (field == 1) values += value else alternatives += value
            } else skip()
        }
        return BundleTarget.Dimension(values, alternatives)
    }

    // Field numbers follow google/bundletool commands.proto and targeting.proto
    private class Wire(private val input: InputStream, private var remaining: Long) {
        private var type = -1

        fun fields(read: Wire.(Int) -> Unit) {
            while (remaining > 0) {
                val tag = varint()
                if (tag < 8 || tag > Int.MAX_VALUE) invalid()
                type = (tag and 7).toInt()
                read((tag ushr 3).toInt())
                if (type != -1) invalid()
            }
        }

        fun integer(): Int {
            if (type != 0) invalid()
            type = -1
            val value = varint()
            if (value > Int.MAX_VALUE) invalid()
            return value.toInt()
        }

        fun <T> message(read: (Wire) -> T): T {
            val length = length()
            val child = Wire(input, length)
            val result = read(child)
            child.drain(child.remaining)
            remaining -= length
            return result
        }

        fun string(): String {
            val length = length()
            if (length > 1024) throw BundleException(R.string.bundle_error_limit)
            val bytes = ByteArray(length.toInt())
            for (i in bytes.indices) bytes[i] = byte().toByte()
            return bytes.toString(Charsets.UTF_8)
        }

        fun skip() {
            when (type) {
                0 -> { type = -1; varint() }
                1 -> { type = -1; drain(8) }
                2 -> drain(length())
                5 -> { type = -1; drain(4) }
                else -> invalid()
            }
        }

        private fun length(): Long {
            if (type != 2) invalid()
            type = -1
            val length = varint()
            if (length > remaining) invalid()
            return length
        }

        private fun drain(length: Long) {
            if (length > remaining) invalid()
            repeat(length.toInt()) { byte() }
        }

        private fun varint(): Long {
            var result = 0L
            for (shift in 0..56 step 7) {
                val value = byte()
                result = result or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) return result
            }
            invalid()
        }

        private fun byte(): Int {
            if (remaining <= 0) invalid()
            val value = input.read()
            if (value < 0) invalid()
            remaining--
            return value
        }
    }

    private fun invalid(): Nothing = throw BundleException(R.string.bundle_error_invalid)
}
