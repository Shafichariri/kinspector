package dev.inspector.daemon.archive

import dev.inspector.model.NetworkTransaction
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile

/**
 * Where a fold of one `index.jsonl` stopped, and whether it can resume there.
 *
 * Route stats and findings both fold a session as it grows and keep the result between reads. That
 * is sound only while the file is append-only, so the cursor keeps what it takes to notice when it
 * stopped being so: the offset after the last complete line folded, the file's `fileKey()`, and its
 * first bytes. One copy of the rule, so the two caches cannot disagree about when to start again.
 *
 * Not thread-safe: the cache that owns a cursor holds its entry's lock around both calls.
 */
class IndexCursor {
    /** The offset just past the last `\n` folded; 0 before the first fold. */
    var offset = 0L
        private set

    private var fileKey: Any? = null

    /** The first bytes of the file as last folded; a replaced file almost never starts the same. */
    private var head = ByteArray(0)

    /**
     * True when what was folded is no longer the start of [index]: it shrank, it is another file, or
     * its first bytes changed. The caller then starts a fresh cursor and a fresh fold.
     */
    fun replaced(index: Path): Boolean {
        if (offset == 0L) return false
        if (sizeOf(index) < offset) return true
        if (fileKeyOf(index) != fileKey) return true
        return !headOf(index, head.size).contentEquals(head)
    }

    /**
     * Hands every row on a complete line after [offset] to [onRow], through [IndexTail.read], and
     * returns the new offset. A final line without its newline waits for the next call.
     */
    fun advance(index: Path, onRow: (NetworkTransaction) -> Unit): Long {
        if (offset == 0L) fileKey = fileKeyOf(index)
        offset = IndexTail.read(index, offset, onRow)
        if (head.size < HEAD_BYTES) head = headOf(index, minOf(offset, HEAD_BYTES.toLong()).toInt())
        return offset
    }

    private companion object {
        const val HEAD_BYTES = 256

        fun sizeOf(file: Path): Long = if (file.isRegularFile()) Files.size(file) else -1L

        fun fileKeyOf(file: Path): Any? =
            runCatching { Files.readAttributes(file, BasicFileAttributes::class.java).fileKey() }.getOrNull()

        fun headOf(file: Path, bytes: Int): ByteArray {
            if (bytes <= 0 || !file.isRegularFile()) return ByteArray(0)
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(bytes)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                return buffer.array().copyOf(buffer.position())
            }
        }
    }
}
