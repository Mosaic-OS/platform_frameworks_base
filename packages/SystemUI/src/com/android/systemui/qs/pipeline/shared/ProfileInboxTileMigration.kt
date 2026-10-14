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

package com.android.systemui.qs.pipeline.shared

object ProfileInboxTileMigration {
    private const val LEGACY_TILE_SPEC = "clipboard_share"
    private const val TILE_SPEC = "profile_inbox"

    fun List<TileSpec>.migrateProfileInboxTile(): List<TileSpec> {
        if (none { it.spec == LEGACY_TILE_SPEC }) return this
        return map { if (it.spec == LEGACY_TILE_SPEC) TileSpec.create(TILE_SPEC) else it }.distinct()
    }

    fun Set<TileSpec>.migrateProfileInboxTile(): Set<TileSpec> =
        toList().migrateProfileInboxTile().toSet()
}
