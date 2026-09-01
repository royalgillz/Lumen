package com.lumen.app.data.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.db.dao.BookmarkDao
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.NoteSearchRow
import com.lumen.app.data.db.dao.PageSearchRow
import com.lumen.app.data.db.dao.PageTextDao
import com.lumen.app.data.text.NormalizedMatcher
import com.lumen.app.data.text.SnippetBuilder
import com.lumen.app.data.text.TextNormalizer
import com.lumen.app.domain.model.DocumentTitles
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val pageTextDao: PageTextDao,
    private val documentDao: DocumentDao,
    private val bookmarkDao: BookmarkDao,
) {
    data class Output(val results: List<SearchResult>, val isTruncated: Boolean)

    /**
     * Content search runs against the page-level FTS index: one result row per
     * matching PAGE, with every query token somewhere on that page (line breaks
     * don't matter). Relevance is computed from FTS4 matchinfo — total token hits
     * on the page — plus a boost when the filename also contains query tokens.
     */
    suspend fun search(
        rawQuery: String,
        sanitizedQuery: String,
        filters: SearchFilters = SearchFilters(),
    ): Output {
        // Folder filter is expressed as tree *document IDs*; documents store full
        // tree URI strings. Resolve the selected IDs to the matching URI strings so
        // the filter can run inside the SQL, before the row cap.
        val filterByFolder = filters.folderIds.isNotEmpty()
        val selectedTreeUris = if (filterByFolder) {
            documentDao.distinctTreeUris().filter { treeUriToFolderId(it) in filters.folderIds }
        } else {
            emptyList()
        }
        if (filterByFolder && selectedTreeUris.isEmpty()) return Output(emptyList(), false)

        val minIndexedAt = filters.indexedWithin.cutoffMillis()

        val rows = pageTextDao.searchPages(
            query = sanitizedQuery,
            filterByFolder = if (filterByFolder) 1 else 0,
            treeUris = selectedTreeUris,
            ocrOnly = if (filters.ocrOnly) 1 else 0,
            minIndexedAt = minIndexedAt,
            limit = CANDIDATE_LIMIT + 1,
        )
        val contentTruncated = rows.size > CANDIDATE_LIMIT
        val candidates = if (contentTruncated) rows.take(CANDIDATE_LIMIT) else rows

        // Filename matching shares content search's definition of "matches":
        // the query parses through the same FtsQuerySanitizer path as the
        // MATCH expression (so "802.11b/g" splits identically and quoted
        // phrases group identically in both lanes), and both sides pass
        // through TextNormalizer, so "I20" finds "I-20.pdf". The extension is
        // stripped first — merging name and extension would let "pdf" match
        // every file and create boundary artifacts ("20p").
        // @spec SEARCH-QRY-006, SEARCH-QRY-011
        val queryPhrases = FtsQuerySanitizer.parsePhrases(rawQuery)
            .map { phrase -> phrase.map { it.lowercase() } }
            .distinct()

        // Filename search complements content search. Skip when OCR-only filter is
        // active. Token matching runs in Kotlin because SQLite's lower() is
        // ASCII-only and instr() would force contiguous-phrase semantics; here each
        // token may appear anywhere in the (locale-aware lowercased) basename,
        // while a quoted phrase must appear adjacently.
        // @spec SEARCH-QRY-004
        val filenameRows = if (filters.ocrOnly || queryPhrases.isEmpty()) {
            emptyList()
        } else {
            documentDao.indexedFilenameRows(
                filterByFolder = if (filterByFolder) 1 else 0,
                treeUris = selectedTreeUris,
                minIndexedAt = minIndexedAt,
            )
                .filter { row ->
                    val targets = nameMatchTargets(row.filename, row.customTitle)
                    queryPhrases.all { phrase -> targets.any { nameContains(it, phrase) } }
                }
                .filter { row -> candidates.none { it.docId == row.id } }
                .take(FILENAME_MATCH_LIMIT)
        }

        // Bookmark notes live outside both FTS tables; they get the same
        // Kotlin-side lane as filenames, but with NormalizedMatcher semantics —
        // notes are prose like page text, not identifiers like filenames — so a
        // quoted phrase stays adjacent here too.
        // @spec SEARCH-NOTE-001
        val noteRows = if (filters.ocrOnly || queryPhrases.isEmpty()) {
            emptyList()
        } else {
            bookmarkDao.noteSearchRows(
                filterByFolder = if (filterByFolder) 1 else 0,
                treeUris = selectedTreeUris,
                minIndexedAt = minIndexedAt,
            )
                .filter { row -> NormalizedMatcher.matchesAllPhrases(row.note, rawQuery) }
                .take(NOTE_MATCH_LIMIT)
        }

        // matchinfo is parsed once per row and reused for both the relevance score
        // and the per-result hitCount. A quoted phrase is ONE matchinfo phrase, so
        // it counts once per adjacent occurrence, never once per word.
        // @spec SEARCH-CNT-002
        val rowsWithHits = candidates.map { it to matchInfoTotalHits(it.matchInfo) }

        val contentSorted = when (filters.sortOrder) {
            SortOrder.RELEVANCE -> rowsWithHits
                .map { (row, hits) -> Triple(row, hits, relevanceScore(row, hits, queryPhrases)) }
                .sortedWith(
                    compareByDescending<Triple<PageSearchRow, Int, Int>> { it.third }
                        .thenBy { it.first.filename.lowercase() }
                        .thenBy { it.first.pageNumber }
                )
                .map { it.first to it.second }
            SortOrder.FILENAME -> rowsWithHits
                .sortedWith(compareBy({ it.first.filename.lowercase() }, { it.first.pageNumber }))
            SortOrder.MOST_RECENT -> rowsWithHits
                .sortedWith(
                    compareByDescending<Pair<PageSearchRow, Int>> { it.first.indexedAt ?: 0L }
                        .thenBy { it.first.pageNumber }
                )
        }
        val filenameSorted = when (filters.sortOrder) {
            SortOrder.RELEVANCE, SortOrder.FILENAME -> filenameRows.sortedBy { it.filename.lowercase() }
            SortOrder.MOST_RECENT -> filenameRows.sortedByDescending { it.indexedAt ?: 0L }
        }
        val noteSorted = when (filters.sortOrder) {
            SortOrder.RELEVANCE, SortOrder.FILENAME ->
                noteRows.sortedWith(compareBy({ it.filename.lowercase() }, { it.pageNumber }))
            SortOrder.MOST_RECENT -> noteRows
                .sortedWith(
                    compareByDescending<NoteSearchRow> { it.indexedAt ?: 0L }
                        .thenBy { it.pageNumber }
                )
        }

        // Filename matches go first: when the query names a document, that document
        // is almost always what the user wants, and appending them after content
        // matches let the 200-row cap silently drop them.
        val combined = buildList {
            addAll(filenameSorted.map { row ->
                SearchResult(
                    // Negative synthetic IDs prevent key collisions with page IDs.
                    lineId = -row.id,
                    docId = row.id,
                    uri = row.uri,
                    filename = row.filename,
                    displayTitle = DocumentTitles.displayTitle(row.customTitle, row.derivedTitle, row.filename),
                    pageNumber = 0,
                    lineNumber = 0,
                    snippet = "Filename matches your query.",
                    isOcr = false,
                    folderName = treeUriToFolderName(row.treeUri),
                    isFilenameMatch = true,
                )
            })
            // Note matches rank between the lanes: a note is the user's own
            // annotation — more intentional than body text — but a document
            // named by the query is still the strongest signal.
            // @spec SEARCH-NOTE-002, SEARCH-NOTE-003
            addAll(noteSorted.map { row ->
                val spans = NormalizedMatcher.findMatches(row.note, rawQuery)
                val snippet = SnippetBuilder.build(row.note, spans)
                SearchResult(
                    // Offset keeps synthetic note keys clear of the filename
                    // rows' -docId keys.
                    lineId = -(NOTE_KEY_OFFSET + row.bookmarkId),
                    docId = row.docId,
                    uri = row.docUri,
                    filename = row.filename,
                    displayTitle = DocumentTitles.displayTitle(row.customTitle, row.derivedTitle, row.filename),
                    pageNumber = row.pageNumber,
                    lineNumber = 0,
                    snippet = snippet.text,
                    snippetHighlights = snippet.highlights,
                    isOcr = false,
                    folderName = treeUriToFolderName(row.treeUri),
                    isNoteMatch = true,
                )
            })
            addAll(contentSorted.map { (row, hits) ->
                SearchResult(
                    // Page-granularity results: the stable row key is the page id.
                    lineId = row.pageId,
                    docId = row.docId,
                    uri = row.uri,
                    filename = row.filename,
                    displayTitle = DocumentTitles.displayTitle(row.customTitle, row.derivedTitle, row.filename),
                    pageNumber = row.pageNumber,
                    lineNumber = 0,
                    snippet = "", // filled from original text below, displayed rows only
                    isOcr = row.isOcr,
                    folderName = treeUriToFolderName(row.treeUri),
                    isFilenameMatch = false,
                    // The viewer computes exact rects itself and starts at the first
                    // occurrence — a per-line rank could never index token rects
                    // reliably (a line with two hits is one FTS row).
                    occurrenceOnPage = 0,
                    hitCount = hits,
                )
            })
        }

        val truncated = contentTruncated || combined.size > RESULT_LIMIT
        val results = buildSnippets(combined.take(RESULT_LIMIT), rawQuery)
        return Output(results, truncated)
    }

    /**
     * Second phase of the search: snippets come from ORIGINAL page text (the
     * FTS column holds normalized text), built in Kotlin for just the displayed
     * rows. Spans come from NormalizedMatcher, so what's bold is exactly what
     * matched; when the matcher finds nothing despite the FTS hit, the row
     * keeps a start-of-page snippet rather than being dropped.
     */
    // @spec SEARCH-SNIP-003, SEARCH-MATCH-004
    private suspend fun buildSnippets(
        results: List<SearchResult>,
        rawQuery: String,
    ): List<SearchResult> {
        val pageIds = results.filter { !it.isFilenameMatch && !it.isNoteMatch }.map { it.lineId }
        if (pageIds.isEmpty()) return results
        val textById = pageTextDao.textsForPages(pageIds).associate { it.pageId to it.text }
        return results.map { result ->
            val text = if (result.isFilenameMatch || result.isNoteMatch) null else textById[result.lineId]
            if (text == null) {
                result
            } else {
                val spans = NormalizedMatcher.findMatches(text, rawQuery)
                val snippet = SnippetBuilder.build(text, spans)
                result.copy(snippet = snippet.text, snippetHighlights = snippet.highlights)
            }
        }
    }

    suspend fun searchPagesInDocument(sanitizedQuery: String, docUri: String): List<Int> =
        pageTextDao.searchPagesInDocument(sanitizedQuery, docUri)

    /** Precomputed matchinfo hits on the page + a boost per normalized phrase
     *  that also appears in the document's name (basename or custom title). */
    private fun relevanceScore(row: PageSearchRow, hits: Int, queryPhrases: List<List<String>>): Int {
        var score = hits
        if (queryPhrases.isNotEmpty()) {
            val targets = nameMatchTargets(row.filename, row.customTitle)
            score += queryPhrases.count { phrase -> targets.any { nameContains(it, phrase) } } * FILENAME_TOKEN_BOOST
        }
        return score
    }

    /** One phrase against one name target: single tokens keep the lane's
     *  contains semantics; a quoted phrase must appear adjacently. */
    // @spec SEARCH-QRY-011
    private fun nameContains(target: String, phrase: List<String>): Boolean =
        if (phrase.size == 1) target.contains(phrase[0])
        else NormalizedMatcher.containsAdjacent(target, phrase)

    /** The document-name match targets of SEARCH-QRY-004: the extension-stripped
     *  normalized basename, plus the normalized custom title when one exists.
     *  Derived titles are not matched — they come from indexed page text. */
    private fun nameMatchTargets(filename: String, customTitle: String?): List<String> =
        listOfNotNull(
            TextNormalizer.normalize(filename.substringBeforeLast('.')).lowercase(),
            customTitle?.let { TextNormalizer.normalize(it).lowercase() }?.takeIf { it.isNotEmpty() },
        )

    /**
     * Parse an FTS4 matchinfo blob in 'pcx' format: [p][c] then, per phrase and
     * column, [hits this row][hits all rows][docs with hits] — 32-bit LE ints.
     * Returns the sum of this-row hits across all phrases (our only column is 0).
     * This exact total is what the per-page badge shows — never capped or rounded.
     */
    // @spec SEARCH-CNT-001
    private fun matchInfoTotalHits(blob: ByteArray?): Int {
        if (blob == null || blob.size < 8) return 0
        return try {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val phrases = buf.int
            val columns = buf.int
            var total = 0
            repeat(phrases * columns) {
                if (buf.remaining() < 12) return total
                total += buf.int
                buf.int
                buf.int
            }
            total
        } catch (_: Exception) {
            0
        }
    }

    private companion object {
        /** Final result-list cap. */
        const val RESULT_LIMIT = 200

        /** Candidate pool ranked in memory. Larger than the result cap so ranking
         *  has slack; fetched in rowid order (no alphabetical bias). */
        const val CANDIDATE_LIMIT = 600

        /** A query token appearing in the filename outweighs several body hits. */
        const val FILENAME_TOKEN_BOOST = 20

        /** Cap on filename-match rows, applied after token filtering in Kotlin. */
        const val FILENAME_MATCH_LIMIT = 200

        /** Cap on note-match rows: notes are few, and a runaway match set must
         *  not crowd content rows out of the shared result cap. */
        const val NOTE_MATCH_LIMIT = 50

        /** Keeps note rows' synthetic negative keys clear of the filename
         *  rows' -docId keys. */
        const val NOTE_KEY_OFFSET = 1_000_000_000L
    }

    private fun treeUriToFolderName(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        return try {
            val parsed = Uri.parse(treeUri)
            val docId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
            if (!docId.isNullOrBlank()) {
                // docId is typically "primary:DCIM/Camera" or "SD1234-5678:Downloads"
                val path = docId.substringAfter(':').ifEmpty { docId }
                val name = path.substringAfterLast('/')
                if (name.isNotBlank()) return name
            }
            // Fallback: extract last meaningful segment from the URI path segments
            parsed.pathSegments
                .lastOrNull { it.isNotBlank() }
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?.ifBlank { null }
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun treeUriToFolderId(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        return runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }
            .getOrDefault(treeUri)
    }
}
