package io.github.zeperus.openpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteSearch
import io.github.zeperus.openpad.domain.SearchHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Searches titles and texts of all notes. Reads files off the main thread, computes hits per file, and never shows results of an older query.
 *
 * [search] is called from the main thread (the search field). [query], [results] and [searching] are Compose state that the
 * search screen reads.
 */
class NoteSearchController(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    private val notes: () -> List<NoteInfo>, // the current list (vm.notes)
    private val openText: () -> Pair<NoteId, String>?, // the open note's id and its current (possibly unsaved) text
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What was typed last. */
    var query by mutableStateOf("")
        private set

    /** The notes that match [query]; empty while a new query is still being searched. */
    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /** True from a non-blank query until its results are published, or until it is replaced, cleared or cancelled. */
    var searching by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** Counts the searches started and cancelled; a search publishes only while it is still the latest one. */
    private var generation = 0L

    /**
     * Texts of internal notes read during the current search session, keyed by note. Only touched on the main thread (from the
     * search coroutine, [search] with a blank query, and [cancel]), like the fields above. Emptied when the search ends.
     */
    private val textCache = HashMap<NoteId, CachedText>()

    /** Searches for [query] after a short pause (typing on). Results of an older query are never shown. */
    fun search(query: String) {
        this.query = query
        val thisSearch = ++generation
        job?.cancel()
        results = emptyList()
        if (query.isBlank()) {
            searching = false
            job = null
            textCache.clear()
            return
        }
        searching = true
        job = scope.launch {
            try {
                delay(DEBOUNCE_MILLIS) // typing on: only the last query is searched
                val texts = readTexts(notes(), openText())
                val hits = withContext(computeDispatcher) { NoteSearch.search(query, texts) }
                if (thisSearch == generation) results = hits
            } finally {
                if (thisSearch == generation) searching = false
            }
        }
    }

    /** Empties the search: no query, no results, nothing running. */
    fun clear() = search("")

    /** Stops a search in progress (the view model ends); nothing is published afterwards. */
    fun cancel() {
        generation++
        job?.cancel()
        job = null
        searching = false
        textCache.clear()
    }

    /**
     * Every note with its text: the open note's text as it is on screen, the file otherwise (null when it cannot be read).
     * An internal note whose [NoteInfo.updatedAt] is unchanged since it was cached is not read again. External documents are
     * always read (another app can change them without the timestamp moving), and failed reads are never cached.
     */
    private suspend fun readTexts(all: List<NoteInfo>, open: Pair<NoteId, String>?): List<Pair<NoteInfo, String?>> {
        val texts = all.map { note ->
            val text = when {
                open != null && note.id == open.first -> open.second
                note.isExternal -> readText(note)
                else -> cachedText(note) ?: readText(note)?.also { textCache[note.id] = CachedText(note.updatedAt, it) }
            }
            note to text
        }
        textCache.keys.retainAll(all.mapTo(HashSet()) { it.id }) // notes that left the list are forgotten
        return texts
    }

    /** The cached text of [note] if it has not changed since it was cached, else null. */
    private fun cachedText(note: NoteInfo): String? =
        textCache[note.id]?.takeIf { it.updatedAt == note.updatedAt }?.text

    /** Side-effect-free read: searching never moves the baseline a later save compares against, and never opens a note. */
    private suspend fun readText(note: NoteInfo): String? = try {
        withTimeoutOrNull(if (note.isExternal) EXTERNAL_READ_MILLIS else INTERNAL_READ_MILLIS) { repository.peekText(note.id) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // a file that cannot be read is simply not searched
    }

    /** The text of a note as it was when the note had [updatedAt]. */
    private class CachedText(val updatedAt: Long, val text: String)

    private companion object {
        const val DEBOUNCE_MILLIS = 150L
        const val EXTERNAL_READ_MILLIS = 1_500L // an external document may live on a slow provider
        const val INTERNAL_READ_MILLIS = 10_000L
    }
}
