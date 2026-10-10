package io.nekohasekai.sagernet.group

/**
 * Minimal INI reader for WireGuard / AmneziaWG `.conf` files, replacing the abandoned
 * org.ini4j dependency (Plan 020). Scoped to exactly the shape those configs use:
 *
 * - repeated sections with the same name (`[Peer]` can appear multiple times),
 * - repeated keys within a section (`Address` can appear multiple times),
 * - `key = value` (whitespace around `=` trimmed; first `=` splits),
 * - `#` starts a comment anywhere on a line, as in wg(8); lines starting with `;` are comments,
 * - section and key names match case-insensitively, as in wg(8) and wg-quick.
 *
 * This is pure Kotlin (no Android / libcore), so it is directly JVM-unit-testable.
 */
class IniConfig private constructor(
    private val sections: List<Section>,
    /** Whether a line held neither a section, a `key = value` pair nor a comment, or a pair came before any section. */
    val hasUnrecognizedLines: Boolean,
) {

    class Section(val name: String) {
        // Preserves insertion order and duplicates.
        private val entries = ArrayList<Pair<String, String>>()

        internal fun add(key: String, value: String) {
            entries.add(key to value)
        }

        /** First value for [key], or null if absent (mirrors ini4j Section.get / section[key]). */
        operator fun get(key: String): String? = entries.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

        /** All values for [key] in order, or null if none (mirrors ini4j Section.getAll). */
        fun getAll(key: String): List<String>? = entries.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }.takeIf { it.isNotEmpty() }
    }

    /** First section named [name], or null (mirrors ini4j Ini.get / ini[name]). */
    operator fun get(name: String): Section? = sections.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** All sections named [name] in order, or null if none (mirrors ini4j Ini.getAll). */
    fun getAll(name: String): List<Section>? = sections.filter { it.name.equals(name, ignoreCase = true) }.takeIf { it.isNotEmpty() }

    /** Names of all sections in order. */
    fun sectionNames(): List<String> = sections.map { it.name }

    companion object {
        private val sectionHeader = Regex("""^\[(.+)]$""")

        fun parse(text: String): IniConfig {
            val sections = ArrayList<Section>()
            var current: Section? = null
            var unrecognized = false
            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith(";")) continue
                val header = sectionHeader.matchEntire(line)
                if (header != null) {
                    current = Section(header.groupValues[1].trim()).also { sections.add(it) }
                    continue
                }
                val eq = line.indexOf('=')
                val key = if (eq < 0) "" else line.substring(0, eq).trim()
                if (key.isEmpty() || current == null) {
                    // Not a key=value line inside a section; ignored like ini4j, but reported.
                    unrecognized = true
                    continue
                }
                current.add(key, line.substring(eq + 1).trim())
            }
            return IniConfig(sections, unrecognized)
        }
    }
}
