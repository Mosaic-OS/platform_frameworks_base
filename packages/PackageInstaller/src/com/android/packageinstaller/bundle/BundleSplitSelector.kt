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
import android.content.pm.parsing.ApkLite
import android.os.Build
import com.android.packageinstaller.R
import java.util.Locale

internal data class BundleApk(val entryName: String, val size: Long, val lite: ApkLite,
    val target: BundleTarget?)

internal object BundleSplitSelector {
    private val densities = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213,
        "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)

    private data class Config(val apk: BundleApk, val abis: Set<String>,
        val densities: Set<Int>, val languages: Set<String>) {
        val opaque: Boolean get() = abis.isEmpty() && densities.isEmpty() && languages.isEmpty()
    }

    fun select(context: Context, apks: List<BundleApk>): List<BundleApk> {
        val bases = apks.filter { it.lite.splitName == null }
        if (bases.isEmpty()) throw BundleException(R.string.bundle_error_no_base)
        if (bases.size != 1) throw BundleException(R.string.bundle_error_mixed)
        val base = bases.single()
        if (apks.any { it.lite.packageName != base.lite.packageName ||
            it.lite.longVersionCode != base.lite.longVersionCode ||
            !it.lite.signingDetails.signaturesMatchExactly(base.lite.signingDetails) } ||
            apks.map { it.lite.splitName }.distinct().size != apks.size) {
            throw BundleException(R.string.bundle_error_mixed)
        }
        val features = apks.filter { it.lite.isFeatureSplit }
        val configs = apks.filter { it != base && !it.lite.isFeatureSplit }.map { config(it) }
        val groups = configs.groupBy { it.apk.lite.configForSplit.orEmpty() }
        val abiGroups = groups.values.filter { group -> group.none { it.opaque } }
            .map { group -> group.flatMap { it.abis }.toSet() }
            .filter { it.isNotEmpty() }
        val supported = if (base.lite.isUse32bitAbi) Build.SUPPORTED_32_BIT_ABIS
            else Build.SUPPORTED_ABIS
        val primaryAbi = supported.firstOrNull { abi -> abiGroups.all { abi in it } }
        if (abiGroups.isNotEmpty() && primaryAbi == null) {
            throw BundleException(R.string.bundle_error_incompatible)
        }
        val chosenAbis = mutableSetOf<String>()
        primaryAbi?.let { chosenAbis += it }
        if (base.lite.isMultiArch) {
            for (abis in listOf(Build.SUPPORTED_32_BIT_ABIS, Build.SUPPORTED_64_BIT_ABIS)) {
                abis.firstOrNull { abi -> abiGroups.all { abi in it } }?.let { chosenAbis += it }
            }
        }
        val locales = context.resources.configuration.locales
        val languages = (0 until locales.size()).map { locales[it].language }.toSet()
        val selected = linkedSetOf(base)
        selected.addAll(features)
        for (group in groups.values) {
            if (group.any { it.opaque }) {
                selected.addAll(group.map { it.apk })
                continue
            }
            val density = BundleTarget.bestDensity(group.flatMap { it.densities }.toSet(),
                context.resources.displayMetrics.densityDpi)
            for (config in group) {
                if ((config.abis.isEmpty() || config.abis.any { it in chosenAbis }) &&
                    (config.densities.isEmpty() || density in config.densities) &&
                    (config.languages.isEmpty() || config.languages.any { it in languages })) {
                    selected += config.apk
                }
            }
        }
        val eligible = configs.filter { config ->
            config.apk.lite.minSdkVersion <= Build.VERSION.SDK_INT &&
                (config.abis.isEmpty() || config.abis.any { it in supported })
        }.map { it.apk }
        if (base.lite.isSplitRequired && selected.size == 1) selected.addAll(eligible)
        while (true) {
            val required = selected.flatMap { it.lite.requiredSplitTypes.orEmpty() }.toSet()
            val provided = selected.flatMap { it.lite.splitTypes.orEmpty() }.toSet()
            val missing = required - provided
            if (missing.isEmpty()) break
            val extra = eligible.filter { it !in selected &&
                it.lite.splitTypes.orEmpty().any { type -> type in missing } }
            if (extra.isEmpty()) throw BundleException(R.string.bundle_error_incomplete)
            // A required split type outranks language and density pruning
            selected.addAll(extra)
        }
        if (base.lite.isSplitRequired && selected.size == 1) {
            throw BundleException(R.string.bundle_error_incomplete)
        }
        val splitNames = selected.mapNotNull { it.lite.splitName }.toSet()
        for (apk in selected) {
            if (apk.lite.minSdkVersion > Build.VERSION.SDK_INT) {
                throw BundleException(R.string.bundle_error_incompatible)
            }
            for (dependency in listOf(apk.lite.usesSplitName, apk.lite.configForSplit)) {
                if (!dependency.isNullOrEmpty() && dependency != "base" && dependency !in splitNames) {
                    throw BundleException(R.string.bundle_error_incomplete)
                }
            }
        }
        return selected.toList()
    }

    private fun config(apk: BundleApk): Config {
        val target = apk.target
        if (target?.unsupported == true) return Config(apk, emptySet(), emptySet(), emptySet())
        val name = apk.lite.splitName.orEmpty().substringAfter("config.", "")
        val abi = BundleTarget.ABI_NAMES.firstOrNull { it.isNotEmpty() &&
            it.replace('-', '_') == name }
        val density = densities[name] ?: name.removeSuffix("dpi").toIntOrNull()
            ?.takeIf { name.endsWith("dpi") && it in 1..10000 }
        val locale = Locale.forLanguageTag(name.replace('_', '-').replace("-r", "-"))
        val language = locale.language.takeIf {
            name.matches(Regex("[a-z]{2,3}([_-]r?[A-Za-z0-9]{2,8})*")) &&
                it in Locale.getISOLanguages()
        }
        return Config(apk,
            target?.abi?.values?.mapNotNull { BundleTarget.ABI_NAMES.getOrNull(it) }?.toSet()
                ?: setOfNotNull(abi),
            target?.density?.values?.filter { it > 0 }?.toSet() ?: setOfNotNull(density),
            target?.languages ?: setOfNotNull(language))
    }
}
