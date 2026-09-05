package de.uwumail.rules

/**
 * Turns a handful of example strings into one readable regex that matches all of
 * them and as little else as possible.
 *
 * The approach is token-level: find the longest common token subsequence across
 * every sample, keep those tokens as literals, and replace what varies between
 * them with the tightest character class the samples justify (`\d+` before
 * `\S+` before `.*?`). That produces patterns a person can still read and edit,
 * which matters because the rule editor shows them the result.
 */
object RegexBuilder {

    private val TOKEN = Regex("""\d+|[A-Za-z]+|\s+|.""")
    private val DIGITS = Regex("""\d+""")
    private val HEX = Regex("""[0-9a-fA-F]+""")
    private val NON_SPACE = Regex("""\S+""")

    /** Returns null when the samples have no shared structure worth a pattern. */
    fun fromSamples(samples: List<String>): String? {
        val cleaned = samples.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        if (cleaned.size == 1) return "^" + escape(cleaned.first()) + "$"

        val tokenized = cleaned.map { tokenize(it) }
        val common = tokenized.reduce { acc, next -> longestCommonSubsequence(acc, next) }
        if (common.isEmpty()) return null

        // A pattern made only of whitespace and punctuation says nothing useful.
        if (common.none { it.any(Char::isLetterOrDigit) }) return null

        val alignments = tokenized.map { alignGaps(it, common) ?: return null }

        val builder = StringBuilder()
        val leading = alignments.map { it.first() }
        if (leading.all { it.isEmpty() }) builder.append('^') else builder.append(gapPattern(leading))

        common.forEachIndexed { index, token ->
            builder.append(literalPattern(token))
            val gaps = alignments.map { it[index + 1] }
            if (index < common.size - 1) {
                builder.append(gapPattern(gaps))
            } else if (gaps.all { it.isEmpty() }) {
                builder.append('$')
            } else {
                builder.append(gapPattern(gaps))
            }
        }
        val result = builder.toString()
        return if (runCatching { Regex(result) }.isSuccess) result else null
    }

    /** Longest run of characters shared by every sample, or null if shorter than [minLength]. */
    fun longestCommonSubstring(samples: List<String>, minLength: Int = 4): String? {
        val cleaned = samples.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        if (cleaned.size == 1) return cleaned.first().takeIf { it.length >= minLength }
        val shortest = cleaned.minBy { it.length }
        val others = cleaned.filter { it !== shortest }
        for (length in shortest.length downTo minLength) {
            for (start in 0..shortest.length - length) {
                val candidate = shortest.substring(start, start + length)
                if (others.all { it.contains(candidate, ignoreCase = true) }) return candidate
            }
        }
        return null
    }

    fun commonPrefix(samples: List<String>, minLength: Int = 4): String? {
        if (samples.size < 2) return null
        var prefix = samples.first()
        samples.drop(1).forEach { prefix = prefix.commonPrefixWith(it, ignoreCase = true) }
        return prefix.trimEnd().takeIf { it.length >= minLength }
    }

    fun commonSuffix(samples: List<String>, minLength: Int = 4): String? {
        if (samples.size < 2) return null
        var suffix = samples.first()
        samples.drop(1).forEach { suffix = suffix.commonSuffixWith(it, ignoreCase = true) }
        return suffix.trimStart().takeIf { it.length >= minLength }
    }

    fun escape(text: String): String = buildString {
        text.forEach { ch ->
            if (ch in """\.[]{}()*+?^$|""") append('\\')
            append(ch)
        }
    }

    // ------------------------------------------------------------- internals

    private fun tokenize(text: String): List<String> =
        TOKEN.findAll(text).map { it.value }.toList()

    private fun literalPattern(token: String): String =
        if (token.isNotEmpty() && token.all(Char::isWhitespace)) """\s+""" else escape(token)

    private fun gapPattern(gaps: List<String>): String {
        val nonEmpty = gaps.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ""
        val core = when {
            nonEmpty.all { DIGITS.matches(it) } -> """\d+"""
            nonEmpty.all { HEX.matches(it) } -> "[0-9a-fA-F]+"
            nonEmpty.all { NON_SPACE.matches(it) } -> """\S+"""
            else -> ".*?"
        }
        val optional = gaps.any { it.isEmpty() }
        return when {
            !optional -> core
            core == ".*?" -> ".*?"
            else -> "(?:$core)?"
        }
    }

    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                table[i + 1][j + 1] =
                    if (a[i].equals(b[j], ignoreCase = false)) table[i][j] + 1
                    else maxOf(table[i][j + 1], table[i + 1][j])
            }
        }
        val result = ArrayDeque<String>()
        var i = a.size
        var j = b.size
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> { result.addFirst(a[i - 1]); i--; j-- }
                table[i - 1][j] >= table[i][j - 1] -> i--
                else -> j--
            }
        }
        return result.toList()
    }

    /**
     * Splits [sample] around the tokens of [common], returning `common.size + 1`
     * gap strings. Null when the sample does not actually contain the subsequence.
     */
    private fun alignGaps(sample: List<String>, common: List<String>): List<String>? {
        val gaps = ArrayList<String>(common.size + 1)
        var cursor = 0
        for (token in common) {
            val found = (cursor until sample.size).firstOrNull { sample[it] == token } ?: return null
            gaps += sample.subList(cursor, found).joinToString("")
            cursor = found + 1
        }
        gaps += sample.subList(cursor, sample.size).joinToString("")
        return gaps
    }
}
