package dev.inspector.daemon.service

import dev.inspector.daemon.archive.IndexCursor
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import java.nio.file.Path

/**
 * Findings for a session, folded from where the last read of it stopped.
 *
 * Computed on read and never on ingest, from every row through an [IndexCursor], never through
 * `queryTransactions`, which clamps to 1000. A cache entry per session and `ruleVersion` keeps the
 * cursor and one accumulator per source; the next read folds only what was appended, and collects
 * the list again only when the row count moved. The rules are asked for on every read, because the
 * `findings` key is read live: an edit changes `ruleVersion`, so the next read misses and folds
 * afresh, and the old entry ages out. `findingDismissals`, read live beside it, is not part of
 * `ruleVersion` and is applied to the reply, so a dismissal reuses the entry and rescans nothing.
 *
 * Memory only, [capacity] entries, least recently read evicted. `inspector mcp` and the CLI each have
 * their own.
 */
class FindingsService(
    private val repository: SessionRepository,
    private val engine: FindingsEngine,
    private val capacity: Int = 4,
) {
    private data class CacheKey(val sessionDir: String, val ruleVersion: String)

    private class Entry(val fold: FindingsEngine.Fold) {
        val cursor = IndexCursor()
        var rows = 0
        var collectedAt = -1
        var collected: FindingsEngine.Collected? = null
    }

    private val entries = object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>) = size > capacity
    }

    /** How many times a list was collected; a test reads it to prove an unchanged session reuses one. */
    @Volatile
    internal var collects = 0
        private set

    /**
     * The report for [sessionDir], narrowed to [category] (null is every one) and cut to [limit];
     * dismissed findings are listed only when [includeDismissed], and always counted.
     */
    fun report(sessionDir: Path, category: String?, limit: Int, includeDismissed: Boolean = false): FindingsReport {
        val rules = engine.current()
        val real = sessionDir.toRealPath()
        val key = CacheKey(real.toString(), rules.ruleVersion)
        val index = SessionLayout.indexFile(real)

        val cached = synchronized(entries) { entries[key] }
        if (cached != null) {
            synchronized(cached) {
                if (!cached.cursor.replaced(index)) return snapshot(cached, index, real, rules, category, limit, includeDismissed)
            }
        }
        val entry = Entry(engine.fold(rules, real) { id, side -> repository.readBody(real, id, side) })
        synchronized(entries) { entries[key] = entry }
        return synchronized(entry) { snapshot(entry, index, real, rules, category, limit, includeDismissed) }
    }

    /** Folds what was appended, collects again if rows arrived, then reports. Under the entry's lock. */
    private fun snapshot(
        entry: Entry,
        index: Path,
        dir: Path,
        rules: FindingsEngine.Rules,
        category: String?,
        limit: Int,
        includeDismissed: Boolean,
    ): FindingsReport {
        entry.cursor.advance(index) { row ->
            entry.rows++
            entry.fold.add(row)
        }
        val collected = entry.collected?.takeIf { entry.collectedAt == entry.rows }
            ?: engine.collect(entry.fold).also {
                collects++
                entry.collected = it
                entry.collectedAt = entry.rows
            }
        // Dismissals are applied in the report, after the cache, so one never forces a rescan.
        return engine.report(dir.fileName.toString(), rules, collected, entry.rows, category, limit, includeDismissed)
    }
}
