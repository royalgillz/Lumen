package com.lumen.app.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.lumen.app.MainActivity
import com.lumen.app.R
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.domain.model.DocumentTitles
import com.lumen.app.ui.common.ThumbnailCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the most recently opened library documents as dynamic launcher
 * shortcuts. The intent is a plain ACTION_VIEW of the stored document URI:
 * library documents open through Lumen's persisted tree grants, and the
 * existing VIEW-intent path resolves the library row and bumps its recency —
 * no URI grant flags on the shortcut.
 *
 * Shortcuts are an accelerator, never a feature the app depends on: every
 * ShortcutManager interaction degrades silently (rate limits, locked user,
 * launchers without shortcut support).
 */
@Singleton
class RecentShortcutsUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentDao: DocumentDao,
    private val documentTitleDao: DocumentTitleDao,
) {

    /** Comparison key for distinctUntilChanged: order + identity + label. */
    private data class Seed(val uri: String, val label: String)

    /** Collect for as long as shortcuts should track recency (the host's
     *  STARTED lifetime); publishes on each distinct change, debounced. */
    // @spec SEARCH-ENTRY-005
    @OptIn(FlowPreview::class)
    suspend fun observe() {
        combine(
            documentDao.observeRecentlyOpened(MAX_RECENT_SHORTCUTS),
            documentTitleDao.observeAll(),
        ) { docs, titles ->
            val customByUri = titles.associate { it.docUri to it.title }
            docs.map { doc ->
                Seed(
                    uri = doc.uri,
                    label = DocumentTitles.displayTitle(
                        customTitle = customByUri[doc.uri],
                        derivedTitle = doc.derivedTitle,
                        filename = doc.filename,
                    ),
                )
            }
        }
            .distinctUntilChanged()
            .debounce(UPDATE_DEBOUNCE_MS)
            .collect { publish(it) }
    }

    // setDynamicShortcuts replaces the whole dynamic set, so documents that
    // left the recents list disappear without explicit removal.
    // @spec SEARCH-ENTRY-006, SEARCH-ENTRY-007
    private fun publish(seeds: List<Seed>) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val room = (manager.maxShortcutCountPerActivity - STATIC_SHORTCUT_COUNT)
                .coerceAtLeast(0)
            manager.dynamicShortcuts = seeds.take(room).map { toShortcut(it) }
        }
    }

    private fun toShortcut(seed: Seed): ShortcutInfo {
        // Icon: page-0 thumbnail only when already cached — rendering one here
        // would open the PDF and contend for the render permit just to decorate
        // a launcher menu. Cache miss falls back to the app icon.
        val icon = ThumbnailCache.get(ThumbnailCache.key(seed.uri, 0))
            ?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(seed.uri))
            .setClass(context, MainActivity::class.java)
        return ShortcutInfo.Builder(context, RecentShortcutNames.shortcutIdFor(seed.uri))
            .setShortLabel(RecentShortcutNames.capLabel(seed.label))
            .setLongLabel(RecentShortcutNames.capLabel(seed.label, RecentShortcutNames.LONG_LABEL_MAX))
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    private companion object {
        const val MAX_RECENT_SHORTCUTS = 3
        // The static "open-search" shortcut shares the per-activity budget.
        const val STATIC_SHORTCUT_COUNT = 1
        const val UPDATE_DEBOUNCE_MS = 500L
    }
}
