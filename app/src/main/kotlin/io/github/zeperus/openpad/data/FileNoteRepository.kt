package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoteContent
import io.github.zeperus.openpad.domain.NoteFileName
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import io.github.zeperus.openpad.domain.NoteNameConflictException
import io.github.zeperus.openpad.domain.NoteNotFoundException
import io.github.zeperus.openpad.domain.NoteNotInTrashException
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.NoteStorageException
import io.github.zeperus.openpad.domain.NoteTitles
import io.github.zeperus.openpad.domain.NoteUnreadableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.CharacterCodingException
import java.util.UUID

/**
 * Notes as real `.md` files below [root]. **The file name is the note's id**, so a file can always be tied back
 * to its note and nothing but the index ever changes when a note is renamed:
 *
 * ```
 * root/notes/<id>.md     active notes (plain UTF-8 Markdown, exactly what the user typed)
 * root/trash/<id>.md     trashed notes
 * root/index.json        metadata only: id, title, timestamps - never note text
 * ```
 *
 * Every operation changes at most one file *name* (Trash/Restore move `<id>.md` between the two directories) and
 * then rewrites the index atomically. Whatever the moment of a crash, [load] can reconstruct a consistent state
 * from the directories without ever assigning a new id to an existing file.
 */
class FileNoteRepository internal constructor(
    private val root: File,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val newId: () -> String,
    private val indexWriter: (File, String) -> Unit,
) : NoteRepository {
    constructor(
        root: File,
        clock: () -> Long = System::currentTimeMillis,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        newId: () -> String = { UUID.randomUUID().toString() },
    ) : this(root, clock, dispatcher, newId, AtomicFiles::writeText)

    private val notesDir = File(root, "notes")
    private val trashDir = File(root, "trash")
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; explicitNulls = false }

    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    @Serializable
    private data class IndexData(val version: Int = 0, val notes: List<Entry> = emptyList())

    @Serializable
    private data class Entry(
        val id: String,
        val title: String = "",
        val createdAt: Long,
        val updatedAt: Long,
        val trashedAt: Long? = null,
        val autoTitle: Boolean = true,
        /** Only present in version-1 indexes, where files were named after their title. Never written. */
        val fileName: String? = null,
    ) {
        fun toInfo() = NoteInfo(NoteId(id), title, createdAt, updatedAt, trashedAt, autoTitle)
    }

    override suspend fun createNote(text: String): NoteInfo = locked { notes ->
        val id = newId().also(::requireValidId)
        val title = NoteFileName.uniqueTitle(
            NoteTitles.derive(text) ?: NoteFileName.DEFAULT_TITLE,
            activeTitles(notes),
        )
        AtomicFiles.writeText(noteFile(id), text) // the file first: a crash now leaves an adoptable `<id>.md`
        val now = clock()
        val entry = Entry(id, title, createdAt = now, updatedAt = now)
        notes[id] = entry
        persist(notes)
        entry.toInfo()
    }

    override suspend fun listNotes(): List<NoteInfo> = locked { notes ->
        notes.values.filter { it.trashedAt == null }.map { it.toInfo() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    override suspend fun listTrash(): List<NoteInfo> = locked { notes ->
        notes.values.filter { it.trashedAt != null }.map { it.toInfo() }
            .sortedByDescending { it.trashedAt }
    }

    override suspend fun openNote(id: NoteId): NoteContent = locked { notes ->
        val entry = notes[id.value] ?: throw NoteNotFoundException(id)
        val text = try {
            AtomicFiles.readTextStrict(fileOf(entry))
        } catch (e: CharacterCodingException) {
            throw NoteUnreadableException(entry.title, e)
        }
        NoteContent(entry.toInfo(), text)
    }

    override suspend fun saveNote(id: NoteId, text: String): NoteInfo = locked { notes ->
        var entry = activeEntry(notes, id)
        AtomicFiles.writeText(noteFile(entry.id), text)
        entry = entry.copy(updatedAt = clock())
        // Follow the first line while the user has not chosen a name. Only the index changes.
        if (entry.autoTitle) {
            val wanted = NoteTitles.derive(text)?.let { NoteFileName.sanitizeOrNull(it) }
            if (wanted != null && !isSameTitleFamily(entry.title, wanted)) {
                val others = activeTitles(notes) - entry.title
                entry = entry.copy(title = NoteFileName.uniqueTitle(wanted, others))
            }
        }
        notes[entry.id] = entry
        persist(notes) // if this fails the text is already safe; only the timestamp/title update is lost
        entry.toInfo()
    }

    override suspend fun renameNote(id: NoteId, newTitle: String): NoteInfo = locked { notes ->
        val entry = activeEntry(notes, id)
        val title = NoteFileName.sanitizeOrNull(newTitle) ?: throw InvalidNoteNameException(newTitle)
        val clash = notes.values.any {
            it.id != entry.id && it.trashedAt == null && it.title.equals(title, ignoreCase = true)
        }
        if (clash) throw NoteNameConflictException(title)
        val renamed = entry.copy(title = title, autoTitle = false)
        notes[entry.id] = renamed
        persist(notes) // a single atomic write: the rename either happened completely or not at all
        renamed.toInfo()
    }

    override suspend fun moveToTrash(id: NoteId): NoteInfo = locked { notes ->
        val entry = activeEntry(notes, id)
        AtomicFiles.move(noteFile(entry.id), trashFile(entry.id))
        val trashed = entry.copy(trashedAt = clock())
        notes[entry.id] = trashed
        persist(notes)
        trashed.toInfo()
    }

    override suspend fun restoreFromTrash(id: NoteId): NoteInfo = locked { notes ->
        val entry = notes[id.value]?.takeIf { it.trashedAt != null } ?: throw NoteNotInTrashException(id)
        AtomicFiles.move(trashFile(entry.id), noteFile(entry.id))
        // The title may have been taken meanwhile; resolve in the index, never by touching another note.
        val restored = entry.copy(
            trashedAt = null,
            title = NoteFileName.uniqueTitle(entry.title, activeTitles(notes)),
        )
        notes[entry.id] = restored
        persist(notes)
        restored.toInfo()
    }

    override suspend fun deletePermanently(id: NoteId): Unit = locked { notes ->
        val entry = notes[id.value]?.takeIf { it.trashedAt != null } ?: throw NoteNotInTrashException(id)
        trashFile(entry.id).delete()
        notes.remove(entry.id)
        persist(notes)
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private fun requireValidId(id: String) {
        if (!ID_PATTERN.matches(id)) throw NoteStorageException("Invalid note id")
    }

    // Ids are validated as UUIDs before they ever reach a path, so they cannot contain separators or "..".
    private fun noteFile(id: String) = File(notesDir, idToFileName(id).also { requireValidId(id) })
    private fun trashFile(id: String) = File(trashDir, idToFileName(id).also { requireValidId(id) })
    private fun fileOf(e: Entry) = if (e.trashedAt != null) trashFile(e.id) else noteFile(e.id)
    private fun idToFileName(id: String) = id + NoteFileName.EXTENSION

    private fun activeEntry(notes: Map<String, Entry>, id: NoteId): Entry =
        notes[id.value]?.takeIf { it.trashedAt == null } ?: throw NoteNotFoundException(id)

    private fun activeTitles(notes: Map<String, Entry>): Set<String> =
        notes.values.filter { it.trashedAt == null }.mapTo(HashSet()) { it.title }

    /** True if [title] is [base] or [base] plus a collision suffix (`Shopping 2`). */
    private fun isSameTitleFamily(title: String, base: String): Boolean =
        title == base || Regex(Regex.escape(base) + """ \d+""").matches(title)

    private suspend fun <T> locked(block: (MutableMap<String, Entry>) -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try {
                block(entries ?: load().also { entries = it })
            } catch (e: Throwable) {
                // The in-memory map may be ahead of the disk after a failed step: reload (and re-reconcile)
                // from disk on the next operation instead of trusting it.
                entries = null
                when (e) {
                    is NoteStorageException -> throw e
                    is java.io.IOException -> throw NoteStorageException(e.message ?: "I/O error", e)
                    else -> throw e
                }
            }
        }
    }

    private fun persist(notes: Map<String, Entry>) {
        val data = IndexData(version = INDEX_VERSION, notes = notes.values.sortedBy { it.createdAt })
        indexWriter(indexFile, json.encodeToString(IndexData.serializer(), data))
    }

    /**
     * Loads the index and reconciles it with what is really on disk. The directories decide which notes exist and
     * whether they are trashed; the index supplies titles and timestamps.
     */
    private fun load(): MutableMap<String, Entry> {
        notesDir.mkdirs()
        trashDir.mkdirs()
        listOf(notesDir, trashDir, root).forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(AtomicFiles.TEMP_SUFFIX) }?.forEach { it.delete() }
        }

        val result = LinkedHashMap<String, Entry>()
        var changed = false

        for (entry in readIndex()) {
            if (!ID_PATTERN.matches(entry.id) || entry.id in result) { changed = true; continue }
            var inNotes = noteFile(entry.id).isFile
            var inTrash = trashFile(entry.id).isFile

            // Version 1: the file may still carry its title as name. Rename it to `<id>.md` (idempotent: if a
            // crash interrupted this, `<id>.md` exists already and the legacy name is simply gone).
            val legacy = entry.fileName
            if (!inNotes && !inTrash && legacy != null && NoteFileName.isSafeFileName(legacy)) {
                val firstDir = if (entry.trashedAt != null) trashDir else notesDir
                val secondDir = if (firstDir == trashDir) notesDir else trashDir
                for (dir in listOf(firstDir, secondDir)) {
                    val old = File(dir, legacy)
                    if (old.isFile) {
                        AtomicFiles.move(old, File(dir, idToFileName(entry.id)))
                        break
                    }
                }
                inNotes = noteFile(entry.id).isFile
                inTrash = trashFile(entry.id).isFile
                changed = true
            }

            val title = entry.title.ifBlank {
                changed = true
                legacy?.let { NoteFileName.titleOf(it) }?.let { NoteFileName.sanitizeOrNull(it) }
                    ?: NoteFileName.DEFAULT_TITLE
            }
            val base = entry.copy(title = title, fileName = null)
            when {
                // The index may be one step behind a crashed move, so the file's location wins.
                inNotes -> result[entry.id] = base.copy(trashedAt = null)
                inTrash -> result[entry.id] = base.copy(trashedAt = entry.trashedAt ?: clock())
                else -> changed = true // the file is gone: drop its metadata
            }
            if (result[entry.id] != entry) changed = true
        }

        // Files the index does not know about: crash right after creating a note, a lost/corrupt index, or an
        // `.md` file placed there by hand.
        for (trashed in listOf(false, true)) {
            val dir = if (trashed) trashDir else notesDir
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(NoteFileName.EXTENSION, ignoreCase = true) }
            for (file in files.orEmpty().sortedBy { it.name }) {
                val stem = NoteFileName.titleOf(file.name)
                val known = result[stem]
                if (known != null && (known.trashedAt != null) == trashed) continue // already claimed

                val modified = file.lastModified().takeIf { it > 0 } ?: clock()
                val id: String
                val title: String
                if (ID_PATTERN.matches(stem) && stem !in result) {
                    id = stem // keeps the identity of a note whose index entry was lost
                    title = readTextOrNull(file)?.let { NoteTitles.derive(it) }
                        ?.let { NoteFileName.sanitizeOrNull(it) } ?: NoteFileName.DEFAULT_TITLE
                } else {
                    id = newId().also(::requireValidId)
                    AtomicFiles.move(file, File(dir, idToFileName(id)))
                    title = NoteFileName.sanitizeOrNull(stem) ?: NoteFileName.DEFAULT_TITLE
                }
                val unique = NoteFileName.uniqueTitle(title, activeTitles(result))
                result[id] = Entry(id, unique, modified, modified, if (trashed) modified else null)
                changed = true
            }
        }
        if (changed) persist(result)
        return result
    }

    private fun readTextOrNull(file: File): String? =
        try { AtomicFiles.readTextStrict(file) } catch (_: Exception) { null }

    private fun readIndex(): List<Entry> {
        if (!indexFile.isFile) return emptyList()
        return try {
            json.decodeFromString(IndexData.serializer(), indexFile.readText(Charsets.UTF_8)).notes
        } catch (e: Exception) {
            // Keep the damaged index for inspection; notes are re-adopted from the directories.
            runCatching { indexFile.copyTo(File(root, "index.json.corrupt"), overwrite = true) }
            emptyList()
        }
    }

    private companion object {
        const val INDEX_VERSION = 2
        val ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
