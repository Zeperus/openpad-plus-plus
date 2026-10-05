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
 * Notes as real `.md` files below [root]:
 *
 * ```
 * root/notes/Shopping.md     active notes
 * root/trash/Old idea.md     trashed notes
 * root/index.json            metadata only (ids, timestamps) - never note text
 * ```
 *
 * The directories are the source of truth for which notes exist; the index adds identity and timestamps.
 * If the index is missing or damaged, files are adopted as new notes, so no text is ever lost to metadata loss.
 */
class FileNoteRepository(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : NoteRepository {
    private val notesDir = File(root, "notes")
    private val trashDir = File(root, "trash")
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    @Serializable
    private data class IndexData(val version: Int = 1, val notes: List<Entry> = emptyList())

    @Serializable
    private data class Entry(
        val id: String,
        val fileName: String,
        val createdAt: Long,
        val updatedAt: Long,
        val trashedAt: Long? = null,
        val autoTitle: Boolean = true,
    ) {
        fun toInfo() = NoteInfo(NoteId(id), fileName, createdAt, updatedAt, trashedAt, autoTitle)
    }

    override suspend fun createNote(text: String): NoteInfo = locked { notes ->
        val used = namesIn(notes, trashed = false)
        val fileName = NoteFileName.unique(NoteTitles.derive(text) ?: NoteFileName.DEFAULT_TITLE, used)
        AtomicFiles.writeText(fileIn(notesDir, fileName), text)
        val now = clock()
        val entry = Entry(newId(), fileName, createdAt = now, updatedAt = now)
        notes[entry.id] = entry
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
        val file = fileIn(dirOf(entry), entry.fileName)
        val text = try {
            AtomicFiles.readTextStrict(file)
        } catch (e: CharacterCodingException) {
            throw NoteUnreadableException(entry.fileName, e)
        }
        NoteContent(entry.toInfo(), text)
    }

    override suspend fun saveNote(id: NoteId, text: String): NoteInfo = locked { notes ->
        var entry = activeEntry(notes, id)
        AtomicFiles.writeText(fileIn(notesDir, entry.fileName), text)
        entry = entry.copy(updatedAt = clock())
        notes[id.value] = entry
        // Follow the first line while the user has not chosen a name, then rename the (already saved) file.
        if (entry.autoTitle) {
            val wanted = NoteTitles.derive(text)?.let { NoteFileName.sanitizeOrNull(it) }
            if (wanted != null && !isSameTitleFamily(entry.fileName, wanted)) {
                val others = namesIn(notes, trashed = false) - entry.fileName
                val newName = NoteFileName.unique(wanted, others)
                AtomicFiles.move(fileIn(notesDir, entry.fileName), fileIn(notesDir, newName))
                entry = entry.copy(fileName = newName)
                notes[id.value] = entry
            }
        }
        persist(notes)
        entry.toInfo()
    }

    override suspend fun renameNote(id: NoteId, newTitle: String): NoteInfo = locked { notes ->
        var entry = activeEntry(notes, id)
        val base = NoteFileName.sanitizeOrNull(newTitle) ?: throw InvalidNoteNameException(newTitle)
        val newName = base + NoteFileName.EXTENSION
        val clash = notes.values.any {
            it.id != entry.id && it.trashedAt == null && it.fileName.equals(newName, ignoreCase = true)
        }
        if (clash) throw NoteNameConflictException(base)
        if (newName != entry.fileName) {
            AtomicFiles.move(fileIn(notesDir, entry.fileName), fileIn(notesDir, newName))
        }
        entry = entry.copy(fileName = newName, autoTitle = false)
        notes[id.value] = entry
        persist(notes)
        entry.toInfo()
    }

    override suspend fun moveToTrash(id: NoteId): NoteInfo = locked { notes ->
        var entry = activeEntry(notes, id)
        val newName = NoteFileName.unique(entry.title(), namesIn(notes, trashed = true))
        AtomicFiles.move(fileIn(notesDir, entry.fileName), fileIn(trashDir, newName))
        entry = entry.copy(fileName = newName, trashedAt = clock())
        notes[id.value] = entry
        persist(notes)
        entry.toInfo()
    }

    override suspend fun restoreFromTrash(id: NoteId): NoteInfo = locked { notes ->
        var entry = notes[id.value]?.takeIf { it.trashedAt != null } ?: throw NoteNotInTrashException(id)
        val newName = NoteFileName.unique(entry.title(), namesIn(notes, trashed = false))
        AtomicFiles.move(fileIn(trashDir, entry.fileName), fileIn(notesDir, newName))
        entry = entry.copy(fileName = newName, trashedAt = null)
        notes[id.value] = entry
        persist(notes)
        entry.toInfo()
    }

    override suspend fun deletePermanently(id: NoteId): Unit = locked { notes ->
        val entry = notes[id.value]?.takeIf { it.trashedAt != null } ?: throw NoteNotInTrashException(id)
        fileIn(trashDir, entry.fileName).delete()
        notes.remove(id.value)
        persist(notes)
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private fun Entry.title() = NoteFileName.titleOf(fileName)

    private fun dirOf(entry: Entry) = if (entry.trashedAt != null) trashDir else notesDir

    private fun activeEntry(notes: Map<String, Entry>, id: NoteId): Entry =
        notes[id.value]?.takeIf { it.trashedAt == null } ?: throw NoteNotFoundException(id)

    private fun namesIn(notes: Map<String, Entry>, trashed: Boolean): Set<String> =
        notes.values.filter { (it.trashedAt != null) == trashed }.mapTo(HashSet()) { it.fileName }

    /** True if [fileName] is [base] or [base] plus a collision suffix (`Shopping 2.md`). */
    private fun isSameTitleFamily(fileName: String, base: String): Boolean {
        val stem = NoteFileName.titleOf(fileName)
        return stem == base || Regex(Regex.escape(base) + """ \d+""").matches(stem)
    }

    /** Resolves [name] inside [dir], refusing anything that could escape it. */
    private fun fileIn(dir: File, name: String): File {
        if (!NoteFileName.isSafeFileName(name)) throw InvalidNoteNameException(name)
        val file = File(dir, name)
        if (file.canonicalFile.parentFile != dir.canonicalFile) throw InvalidNoteNameException(name)
        return file
    }

    private suspend fun <T> locked(block: (MutableMap<String, Entry>) -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try {
                block(entries ?: load().also { entries = it })
            } catch (e: NoteStorageException) {
                throw e
            } catch (e: java.io.IOException) {
                throw NoteStorageException(e.message ?: "I/O error", e)
            }
        }
    }

    private fun persist(notes: Map<String, Entry>) {
        val data = IndexData(notes = notes.values.sortedBy { it.createdAt })
        AtomicFiles.writeText(indexFile, json.encodeToString(IndexData.serializer(), data))
    }

    /** Loads the index and reconciles it with what is really on disk. */
    private fun load(): MutableMap<String, Entry> {
        notesDir.mkdirs()
        trashDir.mkdirs()
        listOf(notesDir, trashDir, root).forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(AtomicFiles.TEMP_SUFFIX) }?.forEach { it.delete() }
        }

        val indexed = readIndex()
        val result = LinkedHashMap<String, Entry>()
        val claimed = HashSet<String>() // "notes/<name>" or "trash/<name>" already matched to an entry
        var changed = false

        for (entry in indexed) {
            if (!NoteFileName.isSafeFileName(entry.fileName) || entry.id in result) { changed = true; continue }
            val expectedTrashed = entry.trashedAt != null
            val inExpected = File(if (expectedTrashed) trashDir else notesDir, entry.fileName).isFile
            val inOther = File(if (expectedTrashed) notesDir else trashDir, entry.fileName).isFile
            when {
                inExpected -> result[entry.id] = entry
                inOther -> { // crash between file move and index write: trust the file system
                    changed = true
                    result[entry.id] = if (expectedTrashed) entry.copy(trashedAt = null)
                    else entry.copy(trashedAt = clock())
                }
                else -> changed = true // file is gone: drop metadata
            }
            result[entry.id]?.let { claimed += dirKey(it) }
        }

        for (trashed in listOf(false, true)) {
            val dir = if (trashed) trashDir else notesDir
            dir.listFiles { f -> f.isFile && f.name.endsWith(NoteFileName.EXTENSION, ignoreCase = true) }
                ?.sortedBy { it.name }?.forEach { file ->
                    if ((if (trashed) "trash/" else "notes/") + file.name in claimed) return@forEach
                    if (!NoteFileName.isSafeFileName(file.name)) return@forEach
                    changed = true
                    val modified = file.lastModified().takeIf { it > 0 } ?: clock()
                    val e = Entry(newId(), file.name, modified, modified, if (trashed) modified else null)
                    result[e.id] = e
                }
        }
        if (changed) persist(result)
        return result
    }

    private fun dirKey(e: Entry) = (if (e.trashedAt != null) "trash/" else "notes/") + e.fileName

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
}
