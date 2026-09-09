package de.uwumail.rules

import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.RuleActionEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.data.db.RuleWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why a rule did not catch a message it was written for.
 *
 * The rule screens could say how much a rule catches, which answers the
 * question about a rule that is too broad. They could not answer the opposite
 * one — here is a message it should have caught and nothing happened — and
 * every reason for that is invisible from the rule's own screen: a scope
 * pointing at another folder, a value one character out, a case-sensitive
 * test, or an earlier rule that stops processing so this one never ran.
 */
class RuleExplainTest {

    private fun context(
        subject: String = "Run failed: CI #4",
        from: String = "notifications@github.com",
        folder: String = "INBOX",
        accountId: Long = 1,
        to: List<String> = listOf("me@example.org"),
        headers: Map<String, List<String>> = mapOf("x-github-event" to listOf("workflow_run"))
    ) = MatchContext(
        accountId = accountId,
        folderPath = folder,
        fromAddress = from,
        fromName = "",
        to = to,
        cc = emptyList(),
        subject = subject,
        body = "",
        headers = headers,
        attachmentNames = emptyList(),
        sizeBytes = 1024
    )

    private fun rule(
        id: Long = 1,
        name: String = "rule",
        enabled: Boolean = true,
        accountId: Long? = null,
        folderPath: String? = null,
        matchMode: MatchMode = MatchMode.ALL,
        stopProcessing: Boolean = false,
        conditions: List<RuleConditionEntity> = emptyList()
    ) = RuleWithDetails(
        rule = RuleEntity(
            id = id,
            name = name,
            enabled = enabled,
            priority = id.toInt(),
            accountId = accountId,
            folderPath = folderPath,
            matchMode = matchMode.name,
            stopProcessing = stopProcessing
        ),
        conditions = conditions.map { it.copy(ruleId = id) },
        actions = listOf(
            RuleActionEntity(ruleId = id, type = ActionType.MOVE_TO_TRASH.name, orderIndex = 0)
        )
    )

    private fun condition(
        field: RuleField = RuleField.SUBJECT,
        operator: RuleOperator = RuleOperator.CONTAINS,
        value: String,
        headerName: String? = null,
        negate: Boolean = false,
        caseSensitive: Boolean = false
    ) = RuleConditionEntity(
        ruleId = 0,
        field = field.name,
        headerName = headerName,
        operator = operator.name,
        value = value,
        negate = negate,
        caseSensitive = caseSensitive
    )

    /**
     * The one that matters: an explanation that disagrees with the engine is
     * worse than none, because it sends you looking in the wrong place.
     */
    @Test
    fun `the explanation agrees with the engine on every rule`() {
        val rules = listOf(
            rule(id = 1, name = "off", enabled = false, conditions = listOf(condition(value = "Run"))),
            rule(id = 2, name = "other account", accountId = 99, conditions = listOf(condition(value = "Run"))),
            rule(id = 3, name = "other folder", folderPath = "Archive", conditions = listOf(condition(value = "Run"))),
            rule(id = 4, name = "no conditions"),
            rule(id = 5, name = "misses", conditions = listOf(condition(value = "Deploy"))),
            rule(id = 6, name = "hits", conditions = listOf(condition(value = "Run"))),
            rule(id = 7, name = "after", conditions = listOf(condition(value = "Run")))
        )
        val ctx = context()

        val checks = RuleExplain.check(ctx, rules)
        val acted = RuleEngine.evaluate(ctx, rules).matchedRules.map { it.id }.toSet()

        assertEquals(acted, checks.filter { it.matched }.map { it.rule.id }.toSet())
    }

    @Test
    fun `and still agrees when a rule stops processing`() {
        val rules = listOf(
            rule(id = 1, name = "first", stopProcessing = true, conditions = listOf(condition(value = "Run"))),
            rule(id = 2, name = "second", conditions = listOf(condition(value = "Run")))
        )
        val ctx = context()

        val checks = RuleExplain.check(ctx, rules)
        val acted = RuleEngine.evaluate(ctx, rules).matchedRules.map { it.id }.toSet()

        assertEquals(acted, checks.filter { it.matched }.map { it.rule.id }.toSet())
        assertEquals(RuleVerdict.NOT_REACHED, checks.first { it.rule.id == 2L }.verdict)
    }

    @Test
    fun `a switched off rule says so`() {
        val checks = RuleExplain.check(
            context(),
            listOf(rule(enabled = false, conditions = listOf(condition(value = "Run"))))
        )

        assertEquals(RuleVerdict.DISABLED, checks.single().verdict)
    }

    @Test
    fun `a rule scoped to another folder says which folder`() {
        val checks = RuleExplain.check(
            context(folder = "INBOX"),
            listOf(rule(folderPath = "Archive", conditions = listOf(condition(value = "Run"))))
        )

        assertEquals(RuleVerdict.OTHER_FOLDER, checks.single().verdict)
        assertEquals("Archive", checks.single().rule.folderPath)
    }

    @Test
    fun `a rule scoped to another account says so`() {
        val checks = RuleExplain.check(
            context(accountId = 1),
            listOf(rule(accountId = 2, conditions = listOf(condition(value = "Run"))))
        )

        assertEquals(RuleVerdict.OTHER_ACCOUNT, checks.single().verdict)
    }

    @Test
    fun `a rule with nothing to test says it can never match`() {
        val checks = RuleExplain.check(context(), listOf(rule()))

        assertEquals(RuleVerdict.NO_CONDITIONS, checks.single().verdict)
    }

    /** The point of the whole screen: the value tested, beside the value held. */
    @Test
    fun `a failing condition carries the message's own value`() {
        val checks = RuleExplain.check(
            context(subject = "Run failed: CI #4"),
            listOf(rule(conditions = listOf(condition(value = "Deploy"))))
        )

        val condition = checks.single().conditions.single()
        assertFalse(condition.matched)
        assertEquals(listOf("Run failed: CI #4"), condition.actual)
    }

    /** The commonest near-miss of all, and unreadable without the two side by side. */
    @Test
    fun `a case sensitive test that just misses shows both values`() {
        val checks = RuleExplain.check(
            context(subject = "Run failed: CI #4"),
            listOf(rule(conditions = listOf(condition(value = "run", caseSensitive = true))))
        )

        val condition = checks.single().conditions.single()
        assertFalse(condition.matched)
        assertEquals(listOf("Run failed: CI #4"), condition.actual)
        assertTrue(condition.condition.caseSensitive)
    }

    @Test
    fun `a field the message has nothing in comes back empty`() {
        val checks = RuleExplain.check(
            context(headers = emptyMap()),
            listOf(rule(conditions = listOf(condition(field = RuleField.LIST_ID, value = "kotlin"))))
        )

        assertTrue(checks.single().conditions.single().actual.isEmpty())
    }

    /** Several values under one condition: every recipient is shown, not just the first. */
    @Test
    fun `a field holding several values reports all of them`() {
        val checks = RuleExplain.check(
            context(to = listOf("me@example.org", "team@example.org")),
            listOf(rule(conditions = listOf(condition(field = RuleField.TO, value = "nobody"))))
        )

        assertEquals(
            listOf("me@example.org", "team@example.org"),
            checks.single().conditions.single().actual
        )
    }

    @Test
    fun `match-all says every condition has to hold and marks the one that did not`() {
        val checks = RuleExplain.check(
            context(),
            listOf(
                rule(
                    matchMode = MatchMode.ALL,
                    conditions = listOf(condition(value = "Run"), condition(value = "Deploy"))
                )
            )
        )

        val check = checks.single()
        assertEquals(RuleVerdict.CONDITIONS_NOT_MET, check.verdict)
        assertTrue(check.requireAll)
        assertEquals(listOf(true, false), check.conditions.map { it.matched })
    }

    @Test
    fun `match-any is only unmet when none of them holds`() {
        val checks = RuleExplain.check(
            context(),
            listOf(
                rule(
                    matchMode = MatchMode.ANY,
                    conditions = listOf(condition(value = "Run"), condition(value = "Deploy"))
                )
            )
        )

        assertEquals(RuleVerdict.MATCHED, checks.single().verdict)
        assertFalse(checks.single().requireAll)
    }

    /** Every rule is accounted for, so a missing one is never mistaken for a passing one. */
    @Test
    fun `every rule gets a verdict`() {
        val rules = (1L..5L).map { id ->
            rule(id = id, name = "rule $id", conditions = listOf(condition(value = "Deploy")))
        }

        assertEquals(5, RuleExplain.check(context(), rules).size)
    }
}
