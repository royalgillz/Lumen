package com.lumen.app.domain.model

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide serialization of external_opens writes and their paired grant
 * operations. Three writers can interleave on the same document: a viewer
 * session's open-record / teardown grant-release, and keep-access invoked from
 * either the viewer's offer banner or a recents row (a package that cannot
 * reach the viewer ViewModel's internals). One shared, non-reentrant mutex —
 * callers must never nest acquisitions: KeepExternalAccessUseCase locks
 * internally, so its callers must NOT hold this lock around the call.
 */
// @spec LIB-EXT-017
object ExternalOpensGate {
    val mutex = Mutex()
}
