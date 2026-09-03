package com.lumen.app.domain.model

/**
 * Selects how content rows are scored under the RELEVANCE sort.
 *
 * Release builds always rank with the production default; debug builds may
 * switch via a Settings toggle persisted as the enum name in the DataStore
 * string preference `debug_scorer_variant`.
 */
// @spec SEARCH-RANK-007
enum class ScorerVariant {
    /** Legacy ranking: raw matchinfo hit total (+ name boost). */
    CURRENT,

    /** Okapi BM25 from matchinfo('pcnalx') (+ name boost). */
    BM25;

    companion object {
        /** The production ranking. */
        val DEFAULT = BM25

        /** Maps a stored preference value; unrecognized or absent values fall back to the default. */
        fun fromPref(value: String?): ScorerVariant =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
