package io.github.zeperus.openpad.data

import io.github.zeperus.openpad.domain.ExternalDocuments
import io.github.zeperus.openpad.domain.ExternalNotSupportedException
import io.github.zeperus.openpad.domain.InvalidNoteNameException
import io.github.zeperus.openpad.domain.NoExternalDocuments
import io.github.zeperus.openpad.domain.NoteSourceUnavailableException
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * root/external-backups/<id>.md   last text written to an external document (recovery copy)
 * ```
 *
 * *External* documents (a `content://` URI chosen with the file picker or "Open with") have an index entry with their
 * URI but no file here: they are read and written in place through [ExternalDocuments].
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
    private val external: ExternalDocuments = NoExternalDocuments,
    /** How long one provider call may take before the document counts as unavailable. */
    private val externalTimeoutMs: Long = EXTERNAL_TIMEOUT_MS,
) : NoteRepository {
    constructor(
        root: File,
        clock: () -> Long = System::currentTimeMillis,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        newId: () -> String = { UUID.randomUUID().toString() },
        external: ExternalDocuments = NoExternalDocuments,
        externalTimeoutMs: Long = EXTERNAL_TIMEOUT_MS,
    ) : this(root, clock, dispatcher, newId, AtomicFiles::writeText, external, externalTimeoutMs)

    private val notesDir = File(root, "notes")
    private val trashDir = File(root, "trash")
    private val indexFile = File(root, "index.json")
    private val backupsDir = File(root, "external-backups")
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
        val favorite: Boolean = false,
        val lastOpenedAt: Long? = null,
        /** Only present in version-1 indexes, where files were named after their title. Never written. */
        val fileName: String? = null,
        /** The `content://` URI of an external document; null for internal notes. */
        val uri: String? = null,
        /** For external documents: whether access survives a restart (picker grants do, most "Open with" grants do not). */
        val persistent: Boolean = true,
        /** For external documents: the file started with a UTF-8 byte order mark, which is kept when writing. */
        val bom: Boolean = false,
    ) {
        fun toInfo() = NoteInfo(NoteId(id), title, createdAt, updatedAt, trashedAt, autoTitle, favorite, lastOpenedAt, uri)
    }

    override suspend fun createNote(text: String): NoteInfo = locked { notes ->
        val id = newId().also(::requireValidId)
        val title = NoteFileName.uniqueTitle(
            NoteTitles.derive(text) ?: NoteFileName.DEFAULT_TITLE,
            activeTitles(notes),
        )
        AtomicFiles.writeText(noteFile(id), text) // the file first: a crash now leaves an adoptable `<id>.md`
        val now = clock()
        val entry = Entry(id, title, createdAt = now, updatedAt = now, lastOpenedAt = now)
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
        if (entry.uri != null) return@locked openExternalContent(notes, entry)
        val text = try {
            AtomicFiles.readTextStrict(fileOf(entry))
        } catch (e: CharacterCodingException) {
            throw NoteUnreadableException(entry.title, e)
        }
        NoteContent(entry.toInfo(), text)
    }

    override suspend fun saveNote(id: NoteId, text: String): NoteInfo = locked { notes ->
        var entry = activeEntry(notes, id)
        if (entry.uri != null) return@locked saveExternal(notes, entry, text)
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
        if (entry.uri != null) throw ExternalNotSupportedException("rename")
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

    override suspend fun setFavorite(id: NoteId, favorite: Boolean): NoteInfo = locked { notes ->
        val entry = activeEntry(notes, id)
        if (entry.favorite == favorite) return@locked entry.toInfo()
        val updated = entry.copy(favorite = favorite)
        notes[entry.id] = updated
        persist(notes)
        updated.toInfo()
    }

    override suspend fun markOpened(id: NoteId): NoteInfo = locked { notes ->
        val updated = activeEntry(notes, id).copy(lastOpenedAt = clock())
        notes[updated.id] = updated
        persist(notes)
        updated.toInfo()
    }

    override suspend fun moveToTrash(id: NoteId): NoteInfo = locked { notes ->
        val entry = activeEntry(notes, id)
        if (entry.uri != null) throw ExternalNotSupportedException("move to Trash")
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

    // ---- External documents ----------------------------------------------------------------------------------

    override suspend fun openExternal(uri: String, persistent: Boolean): NoteInfo = locked { notes ->
        notes.values.firstOrNull { it.uri == uri }?.let { existing ->
            if (persistent && !existing.persistent) {
                val upgraded = existing.copy(persistent = true)
                notes[upgraded.id] = upgraded
                persist(notes)
                return@locked upgraded.toInfo()
            }
            return@locked existing.toInfo()
        }
        val id = newId().also(::requireValidId)
        val now = clock()
        val entry = Entry(
            id = id, title = externalTitle(ext { external.displayName(uri) }), createdAt = now, updatedAt = now,
            autoTitle = false, lastOpenedAt = now, uri = uri, persistent = persistent,
        )
        notes[id] = entry
        persist(notes)
        entry.toInfo()
    }

    override suspend fun forgetExternal(id: NoteId): Unit = locked { notes ->
        val entry = notes[id.value]?.takeIf { it.uri != null } ?: throw NoteNotFoundException(id)
        notes.remove(entry.id)
        persist(notes)
        backupFile(entry.id).delete()
    }

    /** Reads an external document, keeping the entry's title and BOM flag in step with what the provider reports. */
    private suspend fun openExternalContent(notes: MutableMap<String, Entry>, entry: Entry): NoteContent {
        val uri = entry.uri!!
        val bytes = ext { external.read(uri, MAX_EXTERNAL_BYTES) }
        val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val text = try {
            AtomicFiles.decodeStrict(if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes)
        } catch (e: CharacterCodingException) {
            throw NoteUnreadableException(entry.title, e)
        }
        var current = entry
        val name = runCatching { ext { external.displayName(uri) } }.getOrNull()
        if (name != null) {
            val title = externalTitle(name)
            if (title != current.title) current = current.copy(title = title)
        }
        if (hasBom != current.bom) current = current.copy(bom = hasBom)
        if (current != entry) {
            notes[current.id] = current
            persist(notes)
        }
        val writable = runCatching { ext { external.isWritable(uri) } }.getOrDefault(false)
        return NoteContent(current.toInfo(), text, readOnly = !writable)
    }

    /**
     * Writes an external document in place. Providers cannot replace a file atomically, so the new text is first kept
     * in a private recovery copy, and after writing the content is read back and compared: a failed or partial write is
     * reported (the editor keeps the text and retries) instead of being believed.
     */
    private suspend fun saveExternal(notes: MutableMap<String, Entry>, entry: Entry, text: String): NoteInfo {
        val uri = entry.uri!!
        val bytes = ((if (entry.bom) "\uFEFF" else "") + text).toByteArray(Charsets.UTF_8)
        backupsDir.mkdirs()
        AtomicFiles.writeText(backupFile(entry.id), text)
        ext { external.write(uri, bytes) }
        val written = ext { external.read(uri, bytes.size + 16) }
        if (!written.contentEquals(bytes)) throw NoteSourceUnavailableException("The document was not written completely")
        val updated = entry.copy(updatedAt = clock())
        notes[updated.id] = updated
        persist(notes)
        return updated.toInfo()
    }

    private fun backupFile(id: String) = File(backupsDir, idToFileName(id).also { requireValidId(id) })

    /** Provider calls can hang; bound them so that one broken provider cannot freeze every note operation. */
    private suspend fun <T> ext(block: suspend () -> T): T = try {
        withTimeout(externalTimeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw NoteSourceUnavailableException("The document provider did not answer in time", e)
    }

    private fun externalTitle(displayName: String?): String {
        val stem = displayName?.let {
            val lower = it.lowercase()
            when {
                lower.endsWith(".md") -> it.dropLast(3)
                lower.endsWith(".markdown") -> it.dropLast(9)
                else -> it
            }
        }
        return stem?.let { NoteFileName.sanitizeOrNull(it) } ?: NoteFileName.DEFAULT_TITLE
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

    private suspend fun <T> locked(block: suspend (MutableMap<String, Entry>) -> T): T = withContext(dispatcher) {
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

        val index = readIndex()
        // Versions before 3 had no lastOpenedAt: treat the last edit as the last use so Recent is not empty.
        // (Later versions only add fields with defaults; they must not touch what was stored.)
        val seedLastOpened = index.version < LAST_OPENED_SINCE_VERSION
        if (index.version < INDEX_VERSION && index.notes.isNotEmpty()) changed = true // rewrite with the current version

        for (entry in index.notes) {
            if (!ID_PATTERN.matches(entry.id) || entry.id in result) { changed = true; continue }
            if (entry.uri != null) { // an external document: there is no file to reconcile
                // access that did not survive the previous process is gone: forget the entry (the file is untouched)
                if (entry.persistent) result[entry.id] = entry.copy(trashedAt = null, fileName = null) else changed = true
                continue
            }
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
            val base = entry.copy(
                title = title,
                fileName = null,
                lastOpenedAt = entry.lastOpenedAt ?: if (seedLastOpened) entry.updatedAt else null,
            )
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

    private fun readIndex(): IndexData {
        if (!indexFile.isFile) return IndexData(version = INDEX_VERSION)
        return try {
            json.decodeFromString(IndexData.serializer(), indexFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the damaged index for inspection; notes are re-adopted from the directories.
            runCatching { indexFile.copyTo(File(root, "index.json.corrupt"), overwrite = true) }
            IndexData(version = INDEX_VERSION)
        }
    }

    private companion object {
        const val INDEX_VERSION = 4
        const val LAST_OPENED_SINCE_VERSION = 3
        const val MAX_EXTERNAL_BYTES = 8 * 1024 * 1024
        const val EXTERNAL_TIMEOUT_MS = 15_000L
        val ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
