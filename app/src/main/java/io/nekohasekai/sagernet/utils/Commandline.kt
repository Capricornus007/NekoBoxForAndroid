package io.nekohasekai.sagernet.utils

/**
 * Renders process command lines and output for logs, with secrets redacted.
 *
 * Quoting based on: https://github.com/apache/ant/blob/588ce1f/src/main/org/apache/tools/ant/types/Commandline.java
 */
object Commandline {

    private val SENSITIVE_VALUE_FLAGS = setOf(
        "-client-id",
        "--client-id",
        "-key",
        "--key",
        "-password",
        "--password",
        "-pass",
        "--pass",
        "-room",
        "--room",
        "-socks-pass",
        "--socks-pass",
        "-socks-user",
        "--socks-user",
    )

    private val SENSITIVE_OUTPUT_PATTERNS = listOf(
        Regex(
            "(?i)(\\\"" +
                "(?:clientId|key|keyHex|password|roomId|secret|serverPassword|serverUsername|" +
                "socksPass|socksUser|username)" +
                "\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
        ) to "\$1<redacted>\$2",
        Regex("(?i)(\\broom=['\"])[^'\"]+(['\"])") to "\$1<redacted>\$2",
        Regex(
            "(?i)((?:from|to)=['\"])[^'\"]+@" +
                "(?:conference|muc)\\.[^'\"]+(['\"])",
        ) to "\$1<redacted>\$2",
        Regex("(?i)((?:from|to)=['\"])[^'\"]+@[^'\"]+(['\"])") to "\$1<redacted>\$2",
        Regex("(?i)\\b(?!https?://)[a-z][a-z0-9+.-]*://\\S+") to "<redacted>",
        Regex("(?i)(colibri-ws=)\\S+") to "\$1<redacted>",
        Regex(
            "(?i)((?:room(?:\\s+(?:url|id))?|roomID|roomId)" +
                "[^'\\\"\\n]*['\\\"])[^'\\\"\\s<>]+(['\\\"])",
        ) to "\$1<redacted>\$2",
        Regex("(?i)\\b[0-9a-f]{64}\\b") to "<redacted>",
        Regex("(?i)(\\bsession=)[0-9a-f]{8}-[0-9a-f-]{27,}") to "\$1<redacted>",
        Regex("(?i)\\[[^\\]\\s]+]:\\d{1,5}") to "<endpoint>",
        Regex(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}" +
                "(?:%[A-Za-z0-9_.-]+)?(?![0-9a-f:])|" +
                "(?<![0-9a-f:])(?=[0-9a-f:]*::)(?=[0-9a-f:]*[0-9a-f])" +
                "(?:[0-9a-f]{1,4}:){0,7}[0-9a-f]{0,4}::" +
                "(?:[0-9a-f]{1,4}:?){0,7}(?:%[A-Za-z0-9_.-]+)?(?![0-9a-f:])",
        ) to "<ip>",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}\\b") to "<endpoint>",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "<ip>",
        Regex("(?i)(jitsi: joining MUC )\\S+( as )") to "\$1<redacted>\$2",
        Regex("(?i)(jitsi: MUC joined )\\S+(; waiting for peer)") to "\$1<redacted>\$2",
        Regex("(?i)(jitsi: (?:rejoin|reconnected|full reconnect) )\\S+") to "\$1<redacted>",
        Regex("(?i)(session )\\S+( ((?:re)?opened \\(device=))[^)]+(\\))") to "\$1<redacted>\$2<redacted>\$4",
    )

    /**
     * Quote the parts of the given array in way that makes them
     * usable as command line arguments.
     * @param args the list of arguments to quote.
     * @return empty string for null or no command, else every argument split
     * by spaces and quoted by quoting rules.
     */
    fun toString(args: Iterable<String>?): String {
        // empty path return empty string
        args ?: return ""
        // path containing one or more elements
        val result = StringBuilder()
        for (arg in args) {
            if (result.isNotEmpty()) result.append(' ')
            arg.indices.map { arg[it] }.forEach {
                when (it) {
                    ' ', '\\', '"', '\'' -> {
                        result.append('\\') // intentionally no break
                        result.append(it)
                    }

                    else -> result.append(it)
                }
            }
        }
        return result.toString()
    }

    fun toRedactedString(args: Iterable<String>?): String = toString(redact(args))

    fun toRedactedString(args: Array<String>) = toRedactedString(args.asIterable())

    fun redactProcessOutput(line: String): String {
        var redacted = line
        for ((pattern, replacement) in SENSITIVE_OUTPUT_PATTERNS) {
            redacted = pattern.replace(redacted, replacement)
        }
        return redacted
    }

    private fun redact(args: Iterable<String>?): List<String>? {
        args ?: return null
        val redacted = ArrayList<String>()
        var redactNext = false
        for (arg in args) {
            val eq = arg.indexOf('=')
            val flag = if (eq > 0) arg.substring(0, eq) else arg
            when {
                redactNext -> {
                    redacted.add("<redacted>")
                    redactNext = false
                }

                flag in SENSITIVE_VALUE_FLAGS && eq > 0 -> redacted.add("$flag=<redacted>")

                flag in SENSITIVE_VALUE_FLAGS -> {
                    redacted.add(arg)
                    redactNext = true
                }

                else -> redacted.add(arg)
            }
        }
        return redacted
    }
}
