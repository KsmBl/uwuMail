package de.uwumail.rules

import de.uwumail.core.WizardLog
import de.uwumail.core.Json
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.RuleConditionEntity

/**
 * One thing the selected messages have in common, phrased as a rule condition.
 *
 * [othersMatched] is what makes the list trustworthy: every candidate is replayed
 * against the locally cached mail the user did *not* select, so the UI can say
 * "this also catches 340 other mails" before anything is saved.
 */
data class Suggestion(
    val condition: RuleConditionEntity,
    val label: String,
    val detail: String,
    val selectedMatched: Int,
    val othersMatched: Int,
    val precision: Double,
    val kind: Kind
) {
    enum class Kind { SENDER, HEADER, SUBJECT, RECIPIENT, LIST, BODY }

    /** True when the condition catches the selection and nothing else in the cache. */
    val isExact: Boolean get() = othersMatched == 0
}

data class SuggestionReport(
    val suggestions: List<Suggestion>,
    /** Indices into [suggestions] that together form the tightest useful rule. */
    val recommended: List<Int>,
    val recommendedOthersMatched: Int,
    val corpusSize: Int,
    val suggestedRuleName: String
)

/**
 * Derives rule conditions from a set of hand-picked messages, so a rule can be
 * built without writing a regex.
 */
class RuleSuggester {

    /** Headers that change per message and would only ever produce noise. */
    private val ignoredHeaders = setOf(
        "date", "message-id", "received", "subject", "to", "cc", "bcc", "from", "reply-to",
        "return-path", "delivered-to", "mime-version", "content-type",
        "content-transfer-encoding", "dkim-signature", "domainkey-signature",
        "authentication-results", "arc-seal", "arc-message-signature",
        "arc-authentication-results", "x-received", "x-google-smtp-source",
        "x-gm-message-state", "x-spam-score", "x-spam-status", "x-spam-level",
        "x-originating-ip", "user-agent", "thread-index", "thread-topic",
        "references", "in-reply-to", "x-ms-exchange-crosstenant-id"
    )

    fun analyse(
        selected: List<MessageEntity>,
        corpus: List<MessageEntity>,
        folderPathOf: (MessageEntity) -> String = { "" }
    ): SuggestionReport {
        if (selected.isEmpty()) {
            return SuggestionReport(emptyList(), emptyList(), 0, 0, "New rule")
        }

        val selectedIds = selected.mapTo(HashSet()) { it.id }
        val others = corpus.filter { it.id !in selectedIds }
        val selectedContexts = selected.map { MatchContext.of(it, folderPathOf(it)) }
        val otherContexts = others.map { MatchContext.of(it, folderPathOf(it)) }
        WizardLog.write("suggester: ${selected.size} selected, ${others.size} others")

        val candidates = buildList {
            addAll(phase("sender candidates") { senderCandidates(selected) })
            addAll(phase("header candidates") { headerCandidates(selected) })
            addAll(phase("subject candidates") { subjectCandidates(selected) })
            addAll(phase("recipient candidates") { recipientCandidates(selected) })
        }
        WizardLog.write("suggester: ${candidates.size} candidates before scoring")

        val scored = phase("scoring") { candidates
            .distinctBy { it.field + "|" + it.headerName + "|" + it.operator + "|" + it.value }
            .mapNotNull { candidate ->
                WizardLog.write(
                    "  scoring ${candidate.field}/${candidate.operator} " +
                        "'${candidate.value.take(60)}'"
                )
                val hitsSelected = selectedContexts.count { RuleMatcher.matches(candidate.condition, it) }
                // A condition that misses part of the selection is not a candidate at all.
                if (hitsSelected < selected.size) return@mapNotNull null
                val hitsOthers = otherContexts.count { RuleMatcher.matches(candidate.condition, it) }
                Suggestion(
                    condition = candidate.condition,
                    label = candidate.label,
                    detail = describe(hitsOthers, others.size),
                    selectedMatched = hitsSelected,
                    othersMatched = hitsOthers,
                    precision = selected.size.toDouble() / (selected.size + hitsOthers),
                    kind = candidate.kind
                )
            }
            .sortedWith(compareByDescending<Suggestion> { it.precision }.thenBy { it.condition.value.length })
        }
        WizardLog.write("suggester: ${scored.size} scored")

        val recommended = phase("choosing a combination") {
            chooseCombination(scored, selectedContexts, otherContexts)
        }
        val remaining = phase("counting what else it catches") {
            if (recommended.isEmpty()) others.size else {
                val conditions = recommended.map { scored[it].condition }
                otherContexts.count { ctx -> conditions.all { RuleMatcher.matches(it, ctx) } }
            }
        }

        return SuggestionReport(
            suggestions = scored,
            recommended = recommended,
            recommendedOthersMatched = remaining,
            corpusSize = others.size,
            suggestedRuleName = suggestName(selected, scored, recommended)
        )
    }

    /** Times one phase of the analysis into the wizard log. */
    private inline fun <T> phase(what: String, body: () -> T): T {
        WizardLog.write("suggester -> $what")
        val began = System.currentTimeMillis()
        val result = body()
        WizardLog.write("suggester <- $what took ${System.currentTimeMillis() - began}ms")
        return result
    }

    // ------------------------------------------------------------- candidates

    private class Candidate(
        val condition: RuleConditionEntity,
        val label: String,
        val kind: Suggestion.Kind
    ) {
        val field get() = condition.field
        val headerName get() = condition.headerName
        val operator get() = condition.operator
        val value get() = condition.value
    }

    private fun condition(
        field: RuleField,
        operator: RuleOperator,
        value: String,
        headerName: String? = null
    ) = RuleConditionEntity(
        ruleId = 0,
        field = field.name,
        headerName = headerName,
        operator = operator.name,
        value = value,
        caseSensitive = false,
        negate = false
    )

    private fun senderCandidates(selected: List<MessageEntity>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val addresses = selected.mapNotNull { it.fromAddress?.lowercase()?.takeIf(String::isNotBlank) }
        if (addresses.size == selected.size) {
            addresses.distinct().singleOrNull()?.let {
                result += Candidate(
                    condition(RuleField.FROM, RuleOperator.EQUALS, it),
                    "Sender is $it",
                    Suggestion.Kind.SENDER
                )
            }
            addresses.map { it.substringAfterLast('@') }.distinct().singleOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    result += Candidate(
                        condition(RuleField.FROM, RuleOperator.DOMAIN_IS, it),
                        "Sender domain is $it",
                        Suggestion.Kind.SENDER
                    )
                }
            // Shared local part, e.g. every notifications@… address across subdomains.
            addresses.map { it.substringBefore('@') }.distinct().singleOrNull()
                ?.takeIf { it.isNotBlank() && addresses.map { a -> a.substringAfterLast('@') }.distinct().size > 1 }
                ?.let {
                    result += Candidate(
                        condition(RuleField.FROM, RuleOperator.STARTS_WITH, "$it@"),
                        "Sender starts with $it@",
                        Suggestion.Kind.SENDER
                    )
                }
        }

        val names = selected.mapNotNull { it.fromName?.takeIf(String::isNotBlank) }
        if (names.size == selected.size) {
            names.distinct().singleOrNull()?.let {
                result += Candidate(
                    condition(RuleField.FROM_NAME, RuleOperator.EQUALS, it),
                    "Sender name is \"$it\"",
                    Suggestion.Kind.SENDER
                )
            }
        }
        return result
    }

    private fun headerCandidates(selected: List<MessageEntity>): List<Candidate> {
        val headerSets = selected.map { Json.decodeHeaders(it.headersJson) }
        if (headerSets.any { it.isEmpty() }) return emptyList()

        val shared = headerSets.first().keys
            .filter { name -> name !in ignoredHeaders && !name.startsWith("x-received") }
            .filter { name -> headerSets.all { it.containsKey(name) } }

        return shared.flatMap { name ->
            val values = headerSets.map { it[name]?.firstOrNull().orEmpty() }
            when {
                values.any { it.isBlank() } -> emptyList()
                values.distinct().size == 1 -> listOf(
                    Candidate(
                        condition(RuleField.HEADER, RuleOperator.EQUALS, values.first(), name),
                        "Header $name is \"${values.first().take(60)}\"",
                        if (name == "list-id") Suggestion.Kind.LIST else Suggestion.Kind.HEADER
                    )
                )
                else -> RegexBuilder.longestCommonSubstring(values, minLength = 5)?.let { common ->
                    listOf(
                        Candidate(
                            condition(RuleField.HEADER, RuleOperator.CONTAINS, common, name),
                            "Header $name contains \"${common.take(60)}\"",
                            if (name == "list-id") Suggestion.Kind.LIST else Suggestion.Kind.HEADER
                        )
                    )
                }.orEmpty()
            }
        }
    }

    private fun subjectCandidates(selected: List<MessageEntity>): List<Candidate> {
        val subjects = selected.map { it.subject }.filter { it.isNotBlank() }
        if (subjects.size < selected.size) return emptyList()
        val result = mutableListOf<Candidate>()

        if (subjects.distinct().size == 1) {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.EQUALS, subjects.first()),
                "Subject is \"${subjects.first().take(60)}\"",
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.commonPrefix(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.STARTS_WITH, it),
                "Subject starts with \"$it\"",
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.commonSuffix(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.ENDS_WITH, it),
                "Subject ends with \"$it\"",
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.longestCommonSubstring(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.CONTAINS, it),
                "Subject contains \"$it\"",
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.fromSamples(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.REGEX, it),
                "Subject matches the shared pattern",
                Suggestion.Kind.SUBJECT
            )
        }
        return result
    }

    private fun recipientCandidates(selected: List<MessageEntity>): List<Candidate> {
        val perMessage = selected.map { message ->
            (message.toList.split(',') + message.ccList.split(','))
                .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
                .toSet()
        }
        if (perMessage.any { it.isEmpty() }) return emptyList()
        val shared = perMessage.reduce { acc, next -> acc intersect next }
        return shared.map {
            Candidate(
                condition(RuleField.TO_OR_CC, RuleOperator.EQUALS, it),
                "Addressed to $it",
                Suggestion.Kind.RECIPIENT
            )
        }
    }

    // ----------------------------------------------------------------- ranking

    /**
     * Greedily ANDs conditions until nothing outside the selection matches, or
     * until adding another stops helping. Stays at three conditions so the
     * generated rule is still something a person can read.
     */
    private fun chooseCombination(
        scored: List<Suggestion>,
        selectedContexts: List<MatchContext>,
        otherContexts: List<MatchContext>
    ): List<Int> {
        if (scored.isEmpty()) return emptyList()
        val chosen = mutableListOf(0)
        var survivors = otherContexts.filter { RuleMatcher.matches(scored[0].condition, it) }

        while (survivors.isNotEmpty() && chosen.size < 3) {
            WizardLog.write("  combining: ${chosen.size} chosen, ${survivors.size} survivors")
            var bestIndex = -1
            var bestSurvivors = survivors
            for (index in scored.indices) {
                if (index in chosen) continue
                val candidate = scored[index]
                // Only conditions that hold for the whole selection may be ANDed in.
                if (selectedContexts.any { !RuleMatcher.matches(candidate.condition, it) }) continue
                val next = survivors.filter { RuleMatcher.matches(candidate.condition, it) }
                if (next.size < bestSurvivors.size) {
                    bestSurvivors = next
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break
            chosen += bestIndex
            survivors = bestSurvivors
        }
        return chosen
    }

    private fun describe(othersMatched: Int, corpusSize: Int): String = when {
        corpusSize == 0 -> "no other mail cached to compare against"
        othersMatched == 0 -> "matches nothing else in $corpusSize cached mails"
        othersMatched == 1 -> "also matches 1 other cached mail"
        else -> "also matches $othersMatched of $corpusSize other cached mails"
    }

    private fun suggestName(
        selected: List<MessageEntity>,
        scored: List<Suggestion>,
        recommended: List<Int>
    ): String {
        recommended.firstOrNull()?.let { index ->
            val suggestion = scored[index]
            when (suggestion.kind) {
                Suggestion.Kind.SENDER -> selected.firstOrNull()?.fromName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                Suggestion.Kind.LIST -> return "List: ${suggestion.condition.value.take(40)}"
                else -> Unit
            }
        }
        val domain = selected.mapNotNull { it.fromAddress?.substringAfterLast('@') }
            .distinct().singleOrNull()
        return domain ?: selected.firstOrNull()?.subject?.take(40).orEmpty().ifBlank { "New rule" }
    }
}
