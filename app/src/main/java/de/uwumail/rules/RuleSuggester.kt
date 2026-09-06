package de.uwumail.rules

import androidx.annotation.StringRes
import de.uwumail.R
import de.uwumail.core.WizardLog
import de.uwumail.core.Json
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.RuleConditionEntity

/**
 * A phrase for the screen to say, kept as an id and its parts rather than as
 * words: the suggester runs on a background thread with no resources to hand,
 * and the wizard is read in whatever language the phone is in.
 */
data class Phrase(@StringRes val id: Int, val args: List<String> = emptyList()) {
    constructor(@StringRes id: Int, vararg args: String) : this(id, args.toList())
}

/**
 * One thing the selected messages have in common, phrased as a rule condition.
 *
 * [othersMatched] is what makes the list trustworthy: every candidate is replayed
 * against the locally cached mail the user did *not* select, so the UI can say
 * "this also catches 340 other mails" before anything is saved.
 */
data class Suggestion(
    val condition: RuleConditionEntity,
    val label: Phrase,
    val detail: Phrase,
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
    val suggestedRuleName: Phrase
)

/**
 * Derives rule conditions from a set of hand-picked messages, so a rule can be
 * built without writing a regex.
 */
class RuleSuggester {

    /** Past this a header value is machinery, not something to match on. */
    private val MAX_HEADER_VALUE = 200

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
            return SuggestionReport(emptyList(), emptyList(), 0, 0, Phrase(R.string.suggest_new_rule))
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
        val label: Phrase,
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
                    Phrase(R.string.suggest_sender_is, it),
                    Suggestion.Kind.SENDER
                )
            }
            addresses.map { it.substringAfterLast('@') }.distinct().singleOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    result += Candidate(
                        condition(RuleField.FROM, RuleOperator.DOMAIN_IS, it),
                        Phrase(R.string.suggest_sender_domain_is, it),
                        Suggestion.Kind.SENDER
                    )
                }
            // Shared local part, e.g. every notifications@… address across subdomains.
            addresses.map { it.substringBefore('@') }.distinct().singleOrNull()
                ?.takeIf { it.isNotBlank() && addresses.map { a -> a.substringAfterLast('@') }.distinct().size > 1 }
                ?.let {
                    result += Candidate(
                        condition(RuleField.FROM, RuleOperator.STARTS_WITH, "$it@"),
                        Phrase(R.string.suggest_sender_starts_with, it),
                        Suggestion.Kind.SENDER
                    )
                }
        }

        val names = selected.mapNotNull { it.fromName?.takeIf(String::isNotBlank) }
        if (names.size == selected.size) {
            names.distinct().singleOrNull()?.let {
                result += Candidate(
                    condition(RuleField.FROM_NAME, RuleOperator.EQUALS, it),
                    Phrase(R.string.suggest_sender_name_is, it),
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
                // The ignore list above names the machine headers I know of,
                // and a list of names can only ever be behind: every server
                // invents its own, and the long ones are all the same kind of
                // thing — signatures, spam verdicts, routing. A rule quoting a
                // fragment of one would match nothing else anyway.
                values.any { it.length > MAX_HEADER_VALUE } -> emptyList()
                values.distinct().size == 1 -> listOf(
                    Candidate(
                        condition(RuleField.HEADER, RuleOperator.EQUALS, values.first(), name),
                        Phrase(R.string.suggest_header_is, name, values.first().take(60)),
                        if (name == "list-id") Suggestion.Kind.LIST else Suggestion.Kind.HEADER
                    )
                )
                else -> RegexBuilder.longestCommonSubstring(values, minLength = 5)?.let { common ->
                    listOf(
                        Candidate(
                            condition(RuleField.HEADER, RuleOperator.CONTAINS, common, name),
                            Phrase(R.string.suggest_header_contains, name, common.take(60)),
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
                Phrase(R.string.suggest_subject_is, subjects.first().take(60)),
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.commonPrefix(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.STARTS_WITH, it),
                Phrase(R.string.suggest_subject_starts_with, it),
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.commonSuffix(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.ENDS_WITH, it),
                Phrase(R.string.suggest_subject_ends_with, it),
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.longestCommonSubstring(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.CONTAINS, it),
                Phrase(R.string.suggest_subject_contains, it),
                Suggestion.Kind.SUBJECT
            )
        }
        RegexBuilder.fromSamples(subjects)?.let {
            result += Candidate(
                condition(RuleField.SUBJECT, RuleOperator.REGEX, it),
                Phrase(R.string.suggest_subject_pattern),
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
                Phrase(R.string.suggest_addressed_to, it),
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

    private fun describe(othersMatched: Int, corpusSize: Int): Phrase = when {
        corpusSize == 0 -> Phrase(R.string.suggest_nothing_to_compare)
        othersMatched == 0 -> Phrase(R.string.suggest_matches_nothing_else, "$corpusSize")
        othersMatched == 1 -> Phrase(R.string.suggest_matches_one_other)
        else -> Phrase(
            R.string.suggest_matches_others, "$othersMatched", "$corpusSize"
        )
    }

    private fun suggestName(
        selected: List<MessageEntity>,
        scored: List<Suggestion>,
        recommended: List<Int>
    ): Phrase {
        recommended.firstOrNull()?.let { index ->
            val suggestion = scored[index]
            when (suggestion.kind) {
                Suggestion.Kind.SENDER -> selected.firstOrNull()?.fromName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return Phrase(R.string.suggest_name_literal, it) }
                Suggestion.Kind.LIST ->
                    return Phrase(R.string.suggest_name_list, suggestion.condition.value.take(40))
                else -> Unit
            }
        }
        val domain = selected.mapNotNull { it.fromAddress?.substringAfterLast('@') }
            .distinct().singleOrNull()
        val fallback = domain
            ?: selected.firstOrNull()?.subject?.take(40).orEmpty().ifBlank { null }
        return fallback?.let { Phrase(R.string.suggest_name_literal, it) }
            ?: Phrase(R.string.suggest_new_rule)
    }
}
