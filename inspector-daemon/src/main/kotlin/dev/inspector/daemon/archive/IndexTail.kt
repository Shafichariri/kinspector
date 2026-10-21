package dev.inspector.daemon.archive

import dev.inspector.model.InspectorJson
import dev.inspector.model.NetworkTransaction
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.isRegularFile

/**
 * `index.jsonl` read from a byte offset, one complete line at a time.
 *
 * For a reader that folds a session as it grows rather than reading it whole each time: the file is
 * append-only, so whatever lies before the offset a previous read stopped at is unchanged. Only
 * lines ending in `\n` are read. `SessionWriter` writes a row and its newline through a buffer that
 * flushes when full, so a long row can be on disk in pieces, and a reader that took the piece as a
 * line would count a row it could not decode, or skip it for good once the rest arrived.
 */
object IndexTail {

    /**
     * One `index.jsonl` line as a row, or null for a blank or undecodable one. The one decode rule:
     * [SessionRepository.readTransactions] calls this too, so a fold and a whole read skip the same
     * lines and count the same rows.
     */
    fun decode(line: String): NetworkTransaction? {
        if (line.isBlank()) return null
        return runCatching { InspectorJson.decodeFromString<NetworkTransaction>(line) }.getOrNull()
    }

    /**
     * Hands every row on a complete line after [from] to [onRow], in file order, and returns the
     * offset just past the last `\n` it read. A final line without its newline is left for the next
     * read, which starts at the returned offset. A missing file reads as empty.
     */
    fun read(file: Path, from: Long, onRow: (NetworkTransaction) -> Unit): Long {
        if (!file.isRegularFile()) return from
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(from)
            val input = Channels.newInputStream(channel)
            val chunk = ByteArray(BUFFER_BYTES)
            // The part of a line that started in an earlier chunk.
            val pending = ByteArrayOutputStream(1024)
            var offset = from
            var position = from
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                var start = 0
                for (i in 0 until n) {
                    if (chunk[i] != NEWLINE) continue
                    val text = if (pending.size() == 0) {
                        String(chunk, start, i - start, Charsets.UTF_8)
                    } else {
                        pending.write(chunk, start, i - start)
                        pending.toString(Charsets.UTF_8).also { pending.reset() }
                    }
                    decode(text)?.let(onRow)
                    start = i + 1
                    offset = position + start
                }
                pending.write(chunk, start, n - start)
                position += n
            }
            return offset
        }
    }

    private const val NEWLINE = '\n'.code.toByte()

    private const val BUFFER_BYTES = 64 * 1024
}
