package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A JSON body as a tree you can fold — the phone's half of the web's tree, with collapse and raw
 * and without focus or hide, which need a hover the phone does not have.
 *
 * **A Column, never a LazyColumn.** It sits inside the detail screen's own vertical scroll, and a
 * lazy list measured inside a scroll gets unbounded height and throws at runtime. Bounding it with
 * a fixed height instead would put a scroll inside a scroll, which on a phone is the one gesture
 * nobody can aim. The cost is that every visible row is composed; a body big enough for that to
 * matter opens folded below depth 2, the same rule the web uses, at a lower threshold.
 *
 * **Copy and raw never read the tree.** They are handed the text the screen already showed, from
 * the hand-rolled formatter that keeps the body byte for byte. The tree itself is parsed with
 * kotlinx.serialization, which keeps every number's literal text and every key's order but keeps
 * only the last of a duplicated key — acceptable for reading, and the reason the copy is not built
 * from it.
 */

/** One body's view: which containers are folded, and whether the raw text is showing instead. */
internal data class JsonTreeState(val collapsed: Set<String> = emptySet(), val raw: Boolean = false)

/**
 * Every tree's state, by body — `txn:<id>:<side>` or `sig:<id>`. Held by the overlay, so folding a
 * body, leaving it and coming back finds it folded the same way.
 */
internal class JsonTreeStates {
    private val states = mutableStateMapOf<String, JsonTreeState>()
    fun get(key: String): JsonTreeState? = states[key]
    fun set(key: String, state: JsonTreeState) { states[key] = state }
}

/** A container that can fold, with its depth. */
internal data class JsonContainer(val path: String, val depth: Int)

/** One drawn line of the tree. */
internal sealed interface JsonRow {
    val path: String
    val depth: Int

    /** `"key": {` — an expanded container's first line. */
    data class Open(override val path: String, override val depth: Int, val key: String?, val isObject: Boolean) : JsonRow

    /** `}` — its last. */
    data class Close(override val path: String, override val depth: Int, val isObject: Boolean, val comma: Boolean) : JsonRow

    /** `"key": { 6 fields }` — a folded container on one line. */
    data class Summary(
        override val path: String,
        override val depth: Int,
        val key: String?,
        val isObject: Boolean,
        val size: Int,
        val comma: Boolean,
    ) : JsonRow

    /** `"key": value` — a scalar, or an empty container. */
    data class Leaf(override val path: String, override val depth: Int, val key: String?, val value: JsonElement, val comma: Boolean) : JsonRow
}

/** JSON Pointer paths: an ancestor test is then a prefix test that a dotted key cannot fool. */
private fun childPath(path: String, segment: String): String =
    "$path/${segment.replace("~", "~0").replace("/", "~1")}"

private fun children(value: JsonElement): List<Pair<String, JsonElement>> = when (value) {
    is JsonObject -> value.entries.map { it.key to it.value }
    is JsonArray -> value.mapIndexed { i, v -> i.toString() to v }
    else -> emptyList()
}

/** Every non-empty container — the things that can fold. */
internal fun jsonContainers(root: JsonElement): List<JsonContainer> {
    val out = mutableListOf<JsonContainer>()
    fun walk(value: JsonElement, path: String, depth: Int) {
        val kids = children(value)
        if (kids.isEmpty()) return
        out += JsonContainer(path, depth)
        for ((segment, kid) in kids) walk(kid, childPath(path, segment), depth + 1)
    }
    walk(root, "", 0)
    return out
}

/** The tree as the lines it currently draws, given which containers are folded. */
internal fun jsonRows(root: JsonElement, collapsed: Set<String>): List<JsonRow> {
    val rows = mutableListOf<JsonRow>()
    fun walk(value: JsonElement, path: String, depth: Int, key: String?, comma: Boolean) {
        val kids = children(value)
        val isObject = value is JsonObject
        when {
            kids.isEmpty() -> rows += JsonRow.Leaf(path, depth, key, value, comma)
            path in collapsed -> rows += JsonRow.Summary(path, depth, key, isObject, kids.size, comma)
            else -> {
                rows += JsonRow.Open(path, depth, key, isObject)
                kids.forEachIndexed { i, (segment, kid) ->
                    walk(kid, childPath(path, segment), depth + 1, if (isObject) segment else null, i < kids.lastIndex)
                }
                rows += JsonRow.Close(path, depth, isObject, comma)
            }
        }
    }
    walk(root, "", 0, null, false)
    return rows
}

/**
 * The state a body opens with: everything open, unless it is big enough that composing every row
 * would stall the screen — then folded below depth 2, so the shape shows and the bulk waits.
 */
internal fun initialTreeState(root: JsonElement): JsonTreeState {
    val containers = jsonContainers(root)
    if (jsonRows(root, emptySet()).size <= OPEN_ROW_LIMIT) return JsonTreeState()
    return JsonTreeState(collapsed = containers.filter { it.depth >= 2 }.mapTo(mutableSetOf()) { it.path })
}

/**
 * [text] parsed as a JSON object or array, or null — a scalar has nothing to fold and a one-row
 * tree is a worse text block, and anything that does not parse is shown as it arrived.
 */
internal fun parseJsonTree(text: String): JsonElement? {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    return root.takeIf { it is JsonObject || it is JsonArray }
}

@Composable
internal fun JsonTree(
    root: JsonElement,
    /** `Body · 6.2 KB` — what this is and how big, left of the actions. */
    label: String,
    /** What raw shows and copy copies: the whole body, whatever is folded. */
    text: String,
    state: JsonTreeState,
    onState: (JsonTreeState) -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = LocalInspectorColors.current
    val containers = remember(root) { jsonContainers(root) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = colors.onSurfaceMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!state.raw) {
                TreeAction("collapse") {
                    // Every container but the root: a tree folded to one line says nothing.
                    onState(state.copy(collapsed = containers.filter { it.path.isNotEmpty() }.mapTo(mutableSetOf()) { it.path }))
                }
                TreeAction("expand") { onState(state.copy(collapsed = emptySet())) }
            }
            TreeAction("raw", on = state.raw) { onState(state.copy(raw = !state.raw)) }
            CopyButton { onCopy(text) }
        }
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceElevated)
                .padding(vertical = 4.dp),
        ) {
            if (state.raw) {
                Text(
                    text,
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    for (row in jsonRows(root, state.collapsed)) {
                        TreeRow(row) {
                            val folded = if (row.path in state.collapsed) state.collapsed - row.path else state.collapsed + row.path
                            onState(state.copy(collapsed = folded))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeAction(label: String, on: Boolean = false, onClick: () -> Unit) {
    val colors = LocalInspectorColors.current
    Text(
        label,
        color = if (on) colors.onSurface else colors.accent,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colors.divider else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun TreeRow(row: JsonRow, onToggle: () -> Unit) {
    val colors = LocalInspectorColors.current
    val foldable = row is JsonRow.Open || row is JsonRow.Summary
    val scalar = colors.methodPut
    val line = buildAnnotatedString {
        fun punct(s: String) = withStyle(SpanStyle(color = colors.onSurfaceMuted)) { append(s) }
        fun key(k: String?) {
            if (k == null) return
            withStyle(SpanStyle(color = colors.onSurface)) { append(JsonPrimitive(k).toString()) }
            punct(": ")
        }
        when (row) {
            is JsonRow.Open -> { key(row.key); punct(if (row.isObject) "{" else "[") }
            is JsonRow.Close -> { punct(if (row.isObject) "}" else "]"); if (row.comma) punct(",") }
            is JsonRow.Summary -> {
                key(row.key)
                val noun = if (row.isObject) (if (row.size == 1) "field" else "fields") else (if (row.size == 1) "item" else "items")
                withStyle(SpanStyle(color = colors.onSurfaceMuted, fontStyle = FontStyle.Italic)) {
                    append(if (row.isObject) "{ ${row.size} $noun }" else "[ ${row.size} $noun ]")
                }
                if (row.comma) punct(",")
            }
            is JsonRow.Leaf -> {
                key(row.key)
                when (val v = row.value) {
                    is JsonObject -> punct("{}")
                    is JsonArray -> punct("[]")
                    is JsonNull -> withStyle(SpanStyle(color = scalar)) { append("null") }
                    // Strings in the success green, every other scalar in the PUT hue — never the
                    // accent or the error red, so nothing in a body competes with status colour.
                    is JsonPrimitive -> withStyle(SpanStyle(color = if (v.isString) colors.success else scalar)) {
                        // `toString` re-quotes a string; a number is its literal, digits intact.
                        append(v.toString())
                    }
                }
                if (row.comma) punct(",")
            }
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .then(if (foldable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = (4 + row.depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
            if (foldable) {
                Text(if (row is JsonRow.Open) "▼" else "▶", color = colors.onSurfaceMuted, fontSize = 10.sp)
            }
        }
        Text(line, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Past this many rows a tree opens folded below depth 2. Lower than the web's 2,000: every row here
 * is composed at once inside the screen's scroll, and a phone pays for that in the first frame.
 */
private const val OPEN_ROW_LIMIT = 400
