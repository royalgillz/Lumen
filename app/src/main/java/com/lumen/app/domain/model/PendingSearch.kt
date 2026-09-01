package com.lumen.app.domain.model

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Hand-off channel for search requests arriving from launcher surfaces
 * (static shortcut, widget, ACTION_PROCESS_TEXT): MainActivity submits, and
 * every live search surface applies the request. Requests are STICKY — never
 * cleared by a consumer: any number of SearchViewModel instances (Search tab +
 * merged Documents screen can coexist on the back stack) each track their own
 * last-handled id and converge on the same query, so a hidden instance can
 * never starve the visible one. Staleness is handled by a freshness window
 * instead of consumption: hand-offs are applied within moments of submission,
 * so a collector created long after (process restore replaying an old
 * ViewModel, a much-later navigation) must ignore the request rather than
 * resurrect it.
 */
object PendingSearch {
    data class Request(val query: String, val id: Long, val elapsedAt: Long)

    /** Ids must be unique across process restarts — a restored consumer's
     *  saved last-handled id must never collide with a fresh submission. */
    private val counter = AtomicLong(System.currentTimeMillis())
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    /** [query] may be empty — that means "open search, ready to type". */
    fun submit(query: String) {
        _request.value = Request(query, counter.incrementAndGet(), SystemClock.elapsedRealtime())
    }

    /** True while the request is recent enough to act on. */
    fun isFresh(request: Request, now: Long = SystemClock.elapsedRealtime()): Boolean =
        now - request.elapsedAt <= FRESHNESS_WINDOW_MS

    private const val FRESHNESS_WINDOW_MS = 5_000L
}
