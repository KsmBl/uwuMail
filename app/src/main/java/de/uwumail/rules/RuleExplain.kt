package de.uwumail.rules

import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.data.db.RuleWithDetails
import de.uwumail.data.db.appliesToFolder

/** Why a rule did or did not act on one message. */
enum class RuleVerdict {
    /** It acted. */
    MATCHED,

    /** Switched off. */
    DISABLED,

    /** Scoped to a different account. */
    OTHER_ACCOUNT,

    /** Scoped to a different folder. */
    OTHER_FOLDER,

    /** Nothing to test, so it can never match — the engine skips these. */
    NO_CONDITIONS,

    /** Its conditions were tested against this message and did not hold. */
    CONDITIONS_NOT_MET,

    /**
     * Never tested, because an earlier rule matched and stops processing. The
     * commonest reason a rule that looks right does nothing, and invisible
     * from the rule's own screen.
     */
    NOT_REACHED
}

/** One condition, what it was looking for, and what the message actually had. */
data class ConditionCheck(
    val condition: RuleConditionEntity,
    val matched: Boolean,
    /**
     * The message's own value for the field under test. Empty when the message
     * has nothing there at all, which is itself usually the answer.
     */
    val actual: List<String>
)

data class RuleCheck(
    val rule: RuleEntity,
    val verdict: RuleVerdict,
    /** True when every condition has to hold, false when any one will do. */
    val requireAll: Boolean,
    /** Empty unless the conditions were actually reached and tested. */
    val conditions: List<ConditionCheck>
) {
    val matched: Boolean get() = verdict == RuleVerdict.MATCHED
}

/**
 * Runs the rules over one message and says what each of them did, and why.
 *
 * The rule screens can say how much a rule catches, which answers "is this too
 * broad". They cannot answer the opposite and more common question — here is a
 * message the rule was written for, so why did nothing happen to it — and the
 * reasons are mostly invisible: a scope set to another folder, a condition
 * whose value is one character out, a case-sensitive test, or an earlier rule
 * with *stop processing* that meant this one was never reached at all.
 *
 * Deliberately a parallel of [RuleEngine.evaluate] rather than a wrapper round
 * it: the engine returns what to do and drops everything about how it got
 * there. The two are pinned together by a test that runs both over the same
 * message and requires them to agree on every rule.
 */
object RuleExplain {

    fun check(ctx: MatchContext, rules: List<RuleWithDetails>): List<RuleCheck> {
        val checks = mutableListOf<RuleCheck>()
        var stopped = false

        for (entry in rules) {
            val rule = entry.rule
            val requireAll = runCatching { MatchMode.valueOf(rule.matchMode) }
                .getOrDefault(MatchMode.ALL) == MatchMode.ALL

            if (stopped) {
                checks += RuleCheck(rule, RuleVerdict.NOT_REACHED, requireAll, emptyList())
                continue
            }

            val verdict = when {
                !rule.enabled -> RuleVerdict.DISABLED
                rule.accountId != null && rule.accountId != ctx.accountId -> RuleVerdict.OTHER_ACCOUNT
                !rule.appliesToFolder(ctx.folderPath) -> RuleVerdict.OTHER_FOLDER
                entry.conditions.isEmpty() -> RuleVerdict.NO_CONDITIONS
                else -> null
            }
            if (verdict != null) {
                checks += RuleCheck(rule, verdict, requireAll, emptyList())
                continue
            }

            val conditions = entry.conditions.map { condition ->
                ConditionCheck(
                    condition = condition,
                    matched = RuleMatcher.matches(condition, ctx),
                    actual = actualFor(condition, ctx)
                )
            }
            val held = if (requireAll) conditions.all { it.matched }
            else conditions.any { it.matched }

            checks += RuleCheck(
                rule = rule,
                verdict = if (held) RuleVerdict.MATCHED else RuleVerdict.CONDITIONS_NOT_MET,
                requireAll = requireAll,
                conditions = conditions
            )
            if (held && rule.stopProcessing) stopped = true
        }
        return checks
    }

    /** A condition naming a field this version does not know tests nothing. */
    private fun actualFor(condition: RuleConditionEntity, ctx: MatchContext): List<String> {
        val field = runCatching { RuleField.valueOf(condition.field) }.getOrNull()
            ?: return emptyList()
        return RuleMatcher.actualValues(field, condition, ctx)
    }
}
