package dev.inspector.repochecks

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.fail

/**
 * The agent guides: the root `AGENTS.md`, which every agent run loads in full, and one `AGENTS.md`
 * per module, each with a `CLAUDE.md` symlink beside it for Claude Code.
 *
 * The root's budget is the point of the split. It was 149 KB, paid on every run, most of it about
 * modules the run was not touching; nothing but a check stops it creeping back. An `@` import in
 * the root would load a decision file into every run while the root itself stayed under budget, so
 * the budget would be a lie — imports belong in the module guides, which load only where they apply.
 */
class AgentGuideTest {

    private val root = File(RepoFiles.root, "AGENTS.md")
    private val guidePaths = RepoFiles.paths.filter { it == "AGENTS.md" || it.endsWith("/AGENTS.md") }

    // Claude Code's import syntax: `@path` at the start of a line or after whitespace, outside code.
    private val import = Regex("(?:^|\\s)@(\\S+)")

    @Test
    fun the_root_guide_stays_under_its_budget() {
        val bytes = root.readBytes().size
        if (bytes >= ROOT_BUDGET_BYTES) {
            fail(
                "AGENTS.md is $bytes bytes; the budget is under $ROOT_BUDGET_BYTES. Every agent run loads it in full. " +
                    "Move content out — decisions to docs/decisions/, status to docs/STATUS.md, module detail to the " +
                    "module's own AGENTS.md — rather than raising the budget."
            )
        }
    }

    @Test
    fun the_root_guide_imports_nothing() {
        val imports = import.findAll(Markdown.prose(root.readText())).map { "@" + it.groupValues[1] }.toList()
        if (imports.isNotEmpty()) {
            fail(
                "The root AGENTS.md must not @-import anything: an import loads into every run and makes the size " +
                    "budget meaningless. Import from the module guide that needs it instead: ${imports.joinToString()}"
            )
        }
    }

    @Test
    fun every_guide_has_a_claude_md_symlink_beside_it() {
        if (guidePaths.size < 2) fail("Found ${guidePaths.size} AGENTS.md files; the module guides are missing.")
        val expected = guidePaths.map { it.removeSuffix("AGENTS.md") + "CLAUDE.md" } + ".cursorrules"
        val listed = RepoFiles.paths.toSet()
        val problems = expected.mapNotNull { link ->
            val path = File(RepoFiles.root, link).toPath()
            when {
                link !in listed -> "$link: missing (or ignored by git)"
                !Files.isSymbolicLink(path) -> "$link: a copy, not a symlink — copies drift"
                Files.readSymbolicLink(path).toString() != "AGENTS.md" ->
                    "$link: points at ${Files.readSymbolicLink(path)}, not AGENTS.md"
                else -> null
            }
        }
        if (problems.isNotEmpty()) fail("Each AGENTS.md needs a sibling symlink whose target is exactly AGENTS.md:\n  " + problems.joinToString("\n  "))
    }

    @Test
    fun every_guide_import_resolves_to_a_decision_file() {
        val decisions = File(RepoFiles.root, "docs/decisions").canonicalFile
        var seen = 0
        val problems = guidePaths.filter { it != "AGENTS.md" }.flatMap { guide ->
            val dir = File(RepoFiles.root, guide).parentFile
            import.findAll(Markdown.prose(File(RepoFiles.root, guide).readText())).mapNotNull { match ->
                seen++
                val target = File(dir, match.groupValues[1]).canonicalFile
                when {
                    target.parentFile != decisions -> "$guide: @${match.groupValues[1]} is not a file in docs/decisions/"
                    !target.isFile -> "$guide: @${match.groupValues[1]} does not exist"
                    else -> null
                }
            }.toList()
        }
        // Thirteen guides with no import between them would pass the loop above.
        if (seen == 0) fail("No @ import found in any module guide; refusing to check nothing.")
        if (problems.isNotEmpty()) fail("Guide imports must name an existing file in docs/decisions/:\n  " + problems.joinToString("\n  "))
    }

    @Test
    fun the_root_architecture_block_lists_exactly_the_modules_in_settings() {
        val includes = Regex("include\\(\"(:[^\"]+)\"\\)").findAll(File(RepoFiles.root, "settings.gradle.kts").readText())
            .map { it.groupValues[1] }.toList()
        if (includes.isEmpty()) fail("No include(\":…\") found in settings.gradle.kts; refusing to check nothing.")
        val text = root.readText()
        val section = text.substringAfter("\n## Architecture\n", missingDelimiterValue = "")
        val block = Markdown.paragraphs(section).firstOrNull { it.fenced }
            ?: fail("The root AGENTS.md has no fenced block under ## Architecture.")
        val listed = block.text.lines().map { it.trim().substringBefore(' ') }.filter { it.startsWith(":") }.toSet()
        // Both directions: a module removed from the build but still listed is as stale as one missing.
        val missing = includes.filterNot { it in listed }
        val gone = listed.filterNot { it in includes }
        if (missing.isNotEmpty() || gone.isNotEmpty()) {
            fail(
                "The root AGENTS.md Architecture block and settings.gradle.kts disagree; the block went stale once " +
                    "before for lack of exactly this check. In settings, not listed: ${missing.joinToString().ifEmpty { "none" }}. " +
                    "Listed, not in settings: ${gone.joinToString().ifEmpty { "none" }}."
            )
        }
    }

    private companion object {
        const val ROOT_BUDGET_BYTES = 15_000
    }
}
