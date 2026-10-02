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
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.parsing.ApkLiteParseUtils
import android.content.pm.parsing.result.ParseTypeImpl
import android.net.Uri
import android.os.PersistableBundle
import android.os.CancellationSignal
import androidx.lifecycle.MutableLiveData
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.PackageUtil
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BundleSessionStager(private val context: Context) {
    internal data class Result(val sessionId: Int, val packageInfo: PackageInfo,
        val snippet: PackageUtil.AppSnippet, val hasObb: Boolean)

    val progress = MutableLiveData(-2)
    private val installer = context.packageManager.packageInstaller
    private var sessionId = PackageInstaller.SessionInfo.INVALID_ID
    @Volatile private var cancelled = false
    @Volatile private var activeInput: InputStream? = null
    private val cancellationSignal = CancellationSignal()
    private var lastProgress = -2

    suspend fun stage(uri: Uri, params: PackageInstaller.SessionParams): Result {
        try {
            return withContext(Dispatchers.IO) {
                val taskContext = coroutineContext
                val check = {
                    taskContext.ensureActive()
                    if (cancelled) throw CancellationException()
                }
                cleanOrphanSessions()
                context.contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal).use { afd ->
                    if (afd == null) throw BundleException(R.string.bundle_error_io)
                    val size = afd.length
                    if (size > BundleArchive.MAX_ARCHIVE) {
                        throw BundleException(R.string.bundle_error_limit)
                    }
                    afd.createInputStream().use { input ->
                        activeInput = input
                        BundleArchive.open(context, input, check) { bytes ->
                            publish(if (size > 0) minOf(35, (bytes * 35 / size).toInt()) else -2)
                        }.use { archive ->
                            val targets = if (archive.format == BundleArchive.Format.BUNDLETOOL) archive.readToc { stream, length ->
                                BundleToc.select(stream, length, context.resources.displayMetrics.densityDpi)
                            } else null
                            if (targets != null && targets.keys.any { it !in archive.entries }) {
                                throw BundleException(R.string.bundle_error_invalid)
                            }
                            val entries = archive.apks.filter { targets == null || it.name in targets }
                            val apks = entries.mapIndexed { index, entry ->
                                check()
                                val lite = BundleScratch.create(context).use { scratch ->
                                    scratch.output().use { archive.copy(entry, it) }
                                    val parsed = ApkLiteParseUtils.parseApkLite(
                                        ParseTypeImpl.forParsingWithoutPlatformCompat(), scratch.file,
                                        ParsingPackageUtils.PARSE_COLLECT_CERTIFICATES)
                                    if (parsed.isError) throw BundleException(R.string.bundle_error_invalid)
                                    parsed.result
                                }
                                publish(35 + (index + 1) * 30 / entries.size)
                                BundleApk(entry.name, entry.size, lite, targets?.get(entry.name))
                            }
                            val selected = BundleSplitSelector.select(context, apks)
                            val base = selected.first().lite
                            params.setAppPackageName(base.packageName)
                            params.setInstallLocation(base.installLocation)
                            val totalSize = selected.sumOf { it.size }
                            params.setSize(totalSize)
                            check()
                            sessionId = installer.createSession(params)
                            installer.openSession(sessionId).use { session ->
                                val metadata = PersistableBundle().apply {
                                    putString(OWNER_KEY, processToken)
                                    putBoolean(OBB_KEY, archive.hasObb)
                                }
                                session.setAppMetadata(metadata)
                                var written = 0L
                                for (apk in selected) {
                                    check()
                                    val name = ApkLiteParseUtils.splitNameToFileName(apk.lite)
                                    BundleArchive.validateName(name)
                                    if ('/' in name || name.toByteArray(Charsets.UTF_8).size > 255) {
                                        throw BundleException(R.string.bundle_error_invalid)
                                    }
                                    session.openWrite(name, 0, apk.size).use { output ->
                                        archive.copy(archive.entries.getValue(apk.entryName), output) { bytes ->
                                            publish(65 + ((written + bytes) * 35 / totalSize).toInt())
                                        }
                                        session.fsync(output)
                                    }
                                    written += apk.size
                                    session.setStagingProgress(written.toFloat() / totalSize)
                                }
                                metadata.putBoolean(READY_KEY, true)
                                session.setAppMetadata(metadata)
                            }
                            check()
                            preview(sessionId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { abandon() }
            throw when (e) {
                is CancellationException, is BundleException -> e
                is ZipException -> BundleException(R.string.bundle_error_invalid)
                is IOException -> BundleException(R.string.bundle_error_io)
                else -> BundleException(R.string.bundle_error_invalid)
            }
        } finally {
            activeInput = null
        }
    }

    suspend fun restore(id: Int): Result = withContext(Dispatchers.IO) { preview(id) }

    @OptIn(DelicateCoroutinesApi::class)
    fun cancel() {
        cancelled = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                cancellationSignal.cancel()
            } catch (_: RuntimeException) {
            }
            try {
                activeInput?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun preview(id: Int): Result {
        val info = installer.getSessionInfo(id) ?: throw BundleException(R.string.bundle_error_invalid)
        val path = info.resolvedBaseApkPath ?: throw BundleException(R.string.bundle_error_no_base)
        // SessionInfo guesses the first APK before commit, so resolve our fixed base name explicitly
        val base = File(File(path).parentFile, "base.apk")
        val metadata = installer.openSession(id).use { it.appMetadata }
        if (!metadata.containsKey(OWNER_KEY) || !metadata.getBoolean(READY_KEY)) {
            throw BundleException(R.string.bundle_error_invalid)
        }
        val packageInfo = context.packageManager.getPackageArchiveInfo(base.parent,
            PackageManager.GET_PERMISSIONS) ?: throw BundleException(R.string.bundle_error_invalid)
        val snippet = PackageUtil.getAppSnippet(context, packageInfo, base)
        return Result(id, packageInfo, snippet, metadata.getBoolean(OBB_KEY))
    }

    private fun cleanOrphanSessions() {
        for (info in installer.mySessions) {
            if (info.isSealed) continue
            try {
                val owner = installer.openSession(info.sessionId).use {
                    it.appMetadata.getString(OWNER_KEY)
                }
                if (owner != null && owner != processToken) installer.abandonSession(info.sessionId)
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun abandon() {
        if (sessionId != PackageInstaller.SessionInfo.INVALID_ID) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun publish(value: Int) {
        if (value != lastProgress) {
            lastProgress = value
            progress.postValue(value)
        }
    }

    companion object {
        private const val OWNER_KEY = "com.android.packageinstaller.bundle.owner"
        private const val OBB_KEY = "com.android.packageinstaller.bundle.obb"
        private const val READY_KEY = "com.android.packageinstaller.bundle.ready"
        private val processToken = UUID.randomUUID().toString()
    }
}
