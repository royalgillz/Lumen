package com.lumen.app.launcher

import java.security.MessageDigest

/**
 * Pure naming rules for dynamic recent-document shortcuts. Kept free of
 * Android types so the rules are unit-testable on the JVM.
 */
object RecentShortcutNames {

    const val SHORT_LABEL_MAX = 24
    const val LONG_LABEL_MAX = 60

    /**
     * Stable id for a document's shortcut. Shortcut ids leak into launcher
     * data and dumpsys, so the URI (which embeds the file path) never appears
     * in them — only a hash prefix, long enough that collisions are unreal.
     */
    // @spec SEARCH-ENTRY-006
    fun shortcutIdFor(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "recent-" + hex.take(16)
    }

    /** Ellipsized hard cap; blank input falls back so ShortcutInfo never
     *  rejects an empty label. */
    // @spec SEARCH-ENTRY-005
    fun capLabel(label: String, max: Int = SHORT_LABEL_MAX): String {
        val trimmed = label.trim().ifEmpty { "PDF" }
        return if (trimmed.length <= max) trimmed else trimmed.take(max - 1) + "…"
    }
}
