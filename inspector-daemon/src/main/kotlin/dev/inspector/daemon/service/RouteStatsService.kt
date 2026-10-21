package dev.inspector.daemon.service

import dev.inspector.daemon.archive.IndexTail
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile

/**
 * Route stats for a session, folded from where the last read of it stopped.
 *
 * Computed on read and never on ingest, so a slow stats read cannot slow archiving. Every row is
 * read, through [IndexTail], and never through `queryTransactions`, which clamps to 1000 — the
 * reason `HarTest` exists. A cache entry per session and filter keeps the accumulator and the byte
 * offset after the last complete line it folded; the next read folds only what was appended, which
 * is sound because `index.jsonl` is append-only. The entry is rebuilt from the start when that stops
 * being true or stops being knowable: the file shrank, was replaced, or no longer starts with the
 * bytes it did, or, under a filter, `markers.jsonl` changed size, since a marker can move a
 * `since:marker(…)` cut.
 *
 * Memory only, [capacity] entries, least recently read evicted. The templates are fixed for the
 * life of the process, as every `config.json` key is, so they are not part of the key.
 */
class RouteStatsService(
    private val repository: SessionRepository,
    private val templates: RouteTemplates,
    private val capacity: Int = 4,
) {
    private data class CacheKey(val sessionDir: String, val filter: String)

    private class Entry(
        val accumulator: RouteStatsAccumulator,
        val markersSize: Long,
    ) {
        var offset = 0L
        var fileKey: Any? = null
        /** The first bytes of the file as last folded; a replaced file almost never starts the same. */
        var head = ByteArray(0)
    }

    private val entries = object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>) = size > capacity
    }

    /**
     * The report for [sessionDir] under [filterText]. Throws `FilterParseException` for a filter
     * that does not parse, with the parser's message for the caller to pass on.
     */
    fun report(sessionDir: Path, filterText: String, sort: StatsSort, limit: Int): RouteStatsReport {
        val filter = filterText.trim()
        val parsed = if (filter.isEmpty()) Filter.MatchAll else FilterParser.parseOrThrow(filter)
        val real = sessionDir.toRealPath()
        val key = CacheKey(real.toString(), filter)
        val index = SessionLayout.indexFile(real)
        // Measured before the markers are read, so a marker written in between is seen next time.
        val markersSize = sizeOf(SessionLayout.markersFile(real))

        val cached = synchronized(entries) { entries[key] }
            ?.takeIf { filter.isEmpty() || it.markersSize == markersSize }
        if (cached != null) {
            synchronized(cached) {
                if (!replaced(index, cached)) return snapshot(cached, index, real, filter, sort, limit)
            }
        }
        val entry = fresh(real, parsed, markersSize)
        synchronized(entries) { entries[key] = entry }
        return synchronized(entry) { snapshot(entry, index, real, filter, sort, limit) }
    }

    /** Folds what was appended since the entry's offset, then reports. Under the entry's lock. */
    private fun snapshot(entry: Entry, index: Path, dir: Path, filter: String, sort: StatsSort, limit: Int) =
        fold(entry, index).let { entry.accumulator.snapshot(dir.fileName.toString(), filter, sort, limit) }

    private fun fresh(dir: Path, parsed: Filter, markersSize: Long): Entry {
        val context = if (parsed == Filter.MatchAll) FilterContext.EMPTY else FilterContext(repository.readMarkers(dir))
        return Entry(RouteStatsAccumulator(templates, parsed, context), markersSize)
    }

    private fun fold(entry: Entry, index: Path) {
        if (entry.offset == 0L) entry.fileKey = fileKeyOf(index)
        entry.offset = IndexTail.read(index, entry.offset) { entry.accumulator.add(it) }
        if (entry.head.size < HEAD_BYTES) entry.head = headOf(index, minOf(entry.offset, HEAD_BYTES.toLong()).toInt())
    }

    /** True when what the entry folded is no longer the start of the file. */
    private fun replaced(index: Path, entry: Entry): Boolean {
        if (entry.offset == 0L) return false
        if (sizeOf(index) < entry.offset) return true
        if (fileKeyOf(index) != entry.fileKey) return true
        return !headOf(index, entry.head.size).contentEquals(entry.head)
    }

    private fun sizeOf(file: Path): Long = if (file.isRegularFile()) Files.size(file) else -1L

    private fun fileKeyOf(file: Path): Any? =
        runCatching { Files.readAttributes(file, BasicFileAttributes::class.java).fileKey() }.getOrNull()

    private fun headOf(file: Path, bytes: Int): ByteArray {
        if (bytes <= 0 || !file.isRegularFile()) return ByteArray(0)
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(bytes)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            return buffer.array().copyOf(buffer.position())
        }
    }

    private companion object {
        const val HEAD_BYTES = 256
    }
}
