package dev.inspector.daemon.archive

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile

/**
 * What a memory cache of a whole-session result compares to know the session changed under it:
 * `index.jsonl`'s size and `fileKey()`, and `markers.jsonl`'s size.
 *
 * For a result that is recomputed whole rather than folded from an offset, as the session diff is:
 * which of its claims are sure depends on every body of a route, so an appended row cannot be added
 * to it. Both files are append-only, so a size that moved is a row or a marker written; a `fileKey`
 * that moved is a file replaced, even at the same size. A missing file reads as size -1 and key null.
 */
data class SessionStamp(val indexSize: Long, val indexKey: Any?, val markersSize: Long) {

    companion object {
        fun of(sessionDir: Path): SessionStamp {
            val index = SessionLayout.indexFile(sessionDir)
            return SessionStamp(
                indexSize = sizeOf(index),
                indexKey = runCatching { Files.readAttributes(index, BasicFileAttributes::class.java).fileKey() }.getOrNull(),
                markersSize = sizeOf(SessionLayout.markersFile(sessionDir)),
            )
        }

        private fun sizeOf(file: Path): Long = if (file.isRegularFile()) Files.size(file) else -1L
    }
}
