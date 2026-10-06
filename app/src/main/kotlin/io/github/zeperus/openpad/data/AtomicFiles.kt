package io.github.zeperus.openpad.data

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    const val TEMP_SUFFIX = ".tmp"

    /**
     * Writes [text] as UTF-8 so that [target] is always either the complete old or the complete new content:
     * write to a sibling temp file, fsync, then atomically rename over the target.
     */
    fun writeText(target: File, text: String) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            move(temp, target, replace = true)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /** Moves [source] to [target]. Refuses to overwrite an existing target unless [replace] is true. */
    fun move(source: File, target: File, replace: Boolean = false) {
        if (!replace && target.exists() && !isSameFile(source, target)) {
            throw java.nio.file.FileAlreadyExistsException(target.path)
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Strict UTF-8 decoding: malformed input throws CharacterCodingException instead of being replaced (replacement would corrupt on re-save). */
    fun readTextStrict(file: File): String = decodeStrict(file.readBytes())

    fun decodeStrict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun isSameFile(a: File, b: File): Boolean =
        try { Files.isSameFile(a.toPath(), b.toPath()) } catch (_: Exception) { false }
}
