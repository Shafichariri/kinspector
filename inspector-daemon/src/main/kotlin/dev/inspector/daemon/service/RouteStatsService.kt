package dev.inspector.daemon.service

import dev.inspector.daemon.archive.IndexCursor
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Route stats for a session, folded from where the last read of it stopped.
 *
 * Computed on read and never on ingest, so a slow stats read cannot slow archiving. Every row is
 * read, through an [IndexCursor], and never through `queryTransactions`, which clamps to 1000 — the
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
        val cursor = IndexCursor()
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
                if (!cached.cursor.replaced(index)) return snapshot(cached, index, real, filter, sort, limit)
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
        entry.cursor.advance(index) { entry.accumulator.add(it) }
    }

    private fun sizeOf(file: Path): Long = if (file.isRegularFile()) Files.size(file) else -1L
}
