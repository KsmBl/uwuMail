package de.uwumail.rules

import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.data.db.RuleDao
import de.uwumail.data.db.appliesToFolder
import de.uwumail.data.db.RuleEntity
import de.uwumail.data.db.RuleWithDetails

data class PlannedAction(
    val type: ActionType,
    val arg: String?,
    val ruleId: Long,
    val ruleName: String
)

data class RulePlan(
    val actions: List<PlannedAction>,
    val matchedRules: List<RuleEntity>
) {
    val matched: Boolean get() = matchedRules.isNotEmpty()

    val suppressNotification: Boolean
        get() = actions.any { it.type == ActionType.SUPPRESS_NOTIFICATION }

    val notifySilently: Boolean
        get() = actions.any { it.type == ActionType.NOTIFY_SILENT }

    val notifyHigh: Boolean
        get() = actions.any { it.type == ActionType.NOTIFY_HIGH }

    /**
     * The single action that takes the message out of its folder. Only the
     * highest-priority one runs; two rules both wanting to move a message would
     * otherwise fight over it.
     */
    val relocation: PlannedAction?
        get() = actions.firstOrNull { it.type.removesFromFolder }

    val markRead: Boolean
        get() = actions.any { it.type == ActionType.MARK_READ }

    val markUnread: Boolean
        get() = actions.any { it.type == ActionType.MARK_UNREAD }

    val flag: Boolean?
        get() = when {
            actions.any { it.type == ActionType.FLAG } -> true
            actions.any { it.type == ActionType.UNFLAG } -> false
            else -> null
        }

    val download: Boolean
        get() = actions.any { it.type == ActionType.DOWNLOAD }

    val copies: List<PlannedAction>
        get() = actions.filter {
            it.type == ActionType.COPY_TO_FOLDER || it.type == ActionType.COPY_TO_LOCAL
        }

    /**
     * For the activity log, which is a record rather than a screen: the action
     * is named by what it is, not by whatever the display language happens to
     * be when it is read back.
     */
    fun summary(): String = actions.joinToString(", ") { action ->
        action.arg?.let { "${action.type.name} -> $it" } ?: action.type.name
    }

    companion object {
        val EMPTY = RulePlan(emptyList(), emptyList())
    }
}

/**
 * Evaluates the user's rules against a message and returns what should happen.
 *
 * Nothing is executed here; [de.uwumail.sync.SyncManager] owns the IMAP side
 * effects so that a rule can be dry-run against cached mail from the UI.
 */
class RuleEngine(private val ruleDao: RuleDao) {

    suspend fun plan(ctx: MatchContext): RulePlan = evaluate(ctx, ruleDao.enabledRules())

    fun plan(ctx: MatchContext, rules: List<RuleWithDetails>): RulePlan = evaluate(ctx, rules)

    /** True when at least one enabled rule inspects the body, which forces a full fetch. */
    suspend fun anyRuleNeedsBody(): Boolean = ruleDao.enabledRules().any { entry ->
        entry.conditions.any {
            runCatching { RuleField.valueOf(it.field) }.getOrNull()?.needsBody == true
        }
    }

    companion object {
        /**
         * Pure evaluation: no database, no side effects. Kept separate so rules
         * can be dry-run from the UI and exercised directly in tests.
         */
        fun evaluate(ctx: MatchContext, rules: List<RuleWithDetails>): RulePlan {
            val actions = mutableListOf<PlannedAction>()
            val matched = mutableListOf<RuleEntity>()

            for (entry in rules) {
                val rule = entry.rule
                if (!rule.enabled) continue
                if (rule.accountId != null && rule.accountId != ctx.accountId) continue
                if (!rule.appliesToFolder(ctx.folderPath)) continue
                if (entry.conditions.isEmpty()) continue

                val requireAll = runCatching { MatchMode.valueOf(rule.matchMode) }
                    .getOrDefault(MatchMode.ALL) == MatchMode.ALL
                if (!RuleMatcher.matchesAll(entry.conditions, ctx, requireAll)) continue

                matched += rule
                entry.actions.sortedBy { it.orderIndex }.forEach { action ->
                    val type = runCatching { ActionType.valueOf(action.type) }.getOrNull()
                        ?: return@forEach
                    actions += PlannedAction(type, action.stringArg, rule.id, rule.name)
                }
                if (rule.stopProcessing) break
            }
            return RulePlan(actions, matched)
        }
    }
}
