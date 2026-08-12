package com.lumen.app.ui.common

/**
 * The one way count strings are rendered anywhere in the app: singular noun at
 * exactly 1 ("1 folder", "1 page"), plural otherwise. Exists so "1 Folders"
 * can never be typed again.
 */
// @spec LIB-PLU-001
fun quantity(count: Int, noun: String, plural: String = noun + "s"): String =
    "$count ${if (count == 1) noun else plural}"
