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

class RuleEngineTest {

    private fun context(
        subject: String = "Run failed: CI #4",
        from: String = "notifications@github.com",
        folder: String = "INBOX",
        accountId: Long = 1,
        headers: Map<String, List<String>> = mapOf("x-github-event" to listOf("workflow_run"))
    ) = MatchContext(
        accountId = accountId,
        folderPath = folder,
        fromAddress = from,
        fromName = "",
        to = listOf("me@example.org"),
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
        priority: Int = 100,
        accountId: Long? = null,
        folderPath: String? = null,
        matchMode: MatchMode = MatchMode.ALL,
        stopProcessing: Boolean = false,
        conditions: List<RuleConditionEntity>,
        actions: List<ActionType>,
        actionArg: String? = null
    ) = RuleWithDetails(
        rule = RuleEntity(
            id = id,
            name = name,
            priority = priority,
            accountId = accountId,
            folderPath = folderPath,
            matchMode = matchMode.name,
            stopProcessing = stopProcessing
        ),
        conditions = conditions.map { it.copy(ruleId = id) },
        actions = actions.mapIndexed { index, type ->
            RuleActionEntity(ruleId = id, type = type.name, stringArg = actionArg, orderIndex = index)
        }
    )

    private fun condition(
        field: RuleField,
        operator: RuleOperator,
        value: String,
        headerName: String? = null,
        negate: Boolean = false
    ) = RuleConditionEntity(
        ruleId = 0,
        field = field.name,
        headerName = headerName,
        operator = operator.name,
        value = value,
        negate = negate
    )

    @Test
    fun `regex on subject drives archive plus notification suppression`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    condition(RuleField.SUBJECT, RuleOperator.REGEX, """Run failed: CI #\d+""")
                ),
                actions = listOf(ActionType.ARCHIVE, ActionType.SUPPRESS_NOTIFICATION)
            )
        )
        val plan = RuleEngine.evaluate(context(), rules)

        assertTrue(plan.matched)
        assertTrue(plan.suppressNotification)
        assertEquals(ActionType.ARCHIVE, plan.relocation?.type)
    }

    @Test
    fun `a rule scoped to another account does not fire`() {
        val rules = listOf(
            rule(
                accountId = 2,
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
                actions = listOf(ActionType.MOVE_TO_TRASH)
            )
        )
        assertFalse(RuleEngine.evaluate(context(accountId = 1), rules).matched)
    }

    @Test
    fun `a rule scoped to another folder does not fire`() {
        val rules = listOf(
            rule(
                folderPath = "Archive",
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
                actions = listOf(ActionType.MARK_READ)
            )
        )
        assertFalse(RuleEngine.evaluate(context(folder = "INBOX"), rules).matched)
    }

    @Test
    fun `stopProcessing prevents later rules from running`() {
        val rules = listOf(
            rule(
                id = 1, priority = 1, stopProcessing = true,
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
                actions = listOf(ActionType.MARK_READ)
            ),
            rule(
                id = 2, priority = 2,
                conditions = listOf(condition(RuleField.FROM, RuleOperator.DOMAIN_IS, "github.com")),
                actions = listOf(ActionType.DELETE_PERMANENTLY)
            )
        )
        val plan = RuleEngine.evaluate(context(), rules)

        assertEquals(1, plan.matchedRules.size)
        assertTrue(plan.markRead)
        assertEquals(null, plan.relocation)
    }

    @Test
    fun `only the highest priority relocation wins`() {
        val rules = listOf(
            rule(
                id = 1, priority = 1,
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
                actions = listOf(ActionType.ARCHIVE)
            ),
            rule(
                id = 2, priority = 2,
                conditions = listOf(condition(RuleField.FROM, RuleOperator.DOMAIN_IS, "github.com")),
                actions = listOf(ActionType.MOVE_TO_TRASH)
            )
        )
        assertEquals(ActionType.ARCHIVE, RuleEngine.evaluate(context(), rules).relocation?.type)
    }

    @Test
    fun `match ANY needs only one condition to hold`() {
        val rules = listOf(
            rule(
                matchMode = MatchMode.ANY,
                conditions = listOf(
                    condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "nothing like this"),
                    condition(RuleField.FROM, RuleOperator.DOMAIN_IS, "github.com")
                ),
                actions = listOf(ActionType.NOTIFY_SILENT)
            )
        )
        val plan = RuleEngine.evaluate(context(), rules)
        assertTrue(plan.matched)
        assertTrue(plan.notifySilently)
    }

    @Test
    fun `negated condition inverts the match`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    condition(RuleField.FROM, RuleOperator.DOMAIN_IS, "github.com", negate = true)
                ),
                actions = listOf(ActionType.MARK_READ)
            )
        )
        assertFalse(RuleEngine.evaluate(context(), rules).matched)
        assertTrue(RuleEngine.evaluate(context(from = "a@elsewhere.test"), rules).matched)
    }

    @Test
    fun `header conditions match case insensitively by default`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    condition(
                        RuleField.HEADER, RuleOperator.EQUALS, "WORKFLOW_RUN",
                        headerName = "X-GitHub-Event"
                    )
                ),
                actions = listOf(ActionType.SUPPRESS_NOTIFICATION)
            )
        )
        assertTrue(RuleEngine.evaluate(context(), rules).suppressNotification)
    }

    @Test
    fun `an invalid regex never matches instead of throwing`() {
        val rules = listOf(
            rule(
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.REGEX, "[unclosed")),
                actions = listOf(ActionType.DELETE_PERMANENTLY)
            )
        )
        assertFalse(RuleEngine.evaluate(context(), rules).matched)
    }

    @Test
    fun `a disabled rule is skipped`() {
        val entry = rule(
            conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
            actions = listOf(ActionType.MARK_READ)
        )
        val disabled = entry.copy(rule = entry.rule.copy(enabled = false))
        assertFalse(RuleEngine.evaluate(context(), listOf(disabled)).matched)
    }

    @Test
    fun `a local copy is planned without relocating the message`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    condition(RuleField.FROM, RuleOperator.DOMAIN_IS, "github.com")
                ),
                actions = listOf(ActionType.COPY_TO_LOCAL),
                actionArg = "Receipts"
            )
        )
        val plan = RuleEngine.evaluate(context(), rules)

        assertEquals(1, plan.copies.size)
        assertEquals(ActionType.COPY_TO_LOCAL, plan.copies.first().type)
        assertEquals("Receipts", plan.copies.first().arg)
        // Copying leaves the original where it is.
        assertEquals(null, plan.relocation)
    }

    @Test
    fun `a move to a local folder counts as the relocation`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    condition(RuleField.FROM, RuleOperator.DOMAIN_IS, "github.com")
                ),
                actions = listOf(ActionType.MOVE_TO_LOCAL),
                actionArg = "Archive 2026"
            )
        )
        val plan = RuleEngine.evaluate(context(), rules)

        assertEquals(ActionType.MOVE_TO_LOCAL, plan.relocation?.type)
        assertEquals("Archive 2026", plan.relocation?.arg)
        assertTrue(plan.copies.isEmpty())
    }

    @Test
    fun `both kinds of copy are planned together`() {
        val rules = listOf(
            rule(
                id = 1,
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
                actions = listOf(ActionType.COPY_TO_FOLDER),
                actionArg = "Backup"
            ),
            rule(
                id = 2,
                conditions = listOf(condition(RuleField.SUBJECT, RuleOperator.CONTAINS, "CI")),
                actions = listOf(ActionType.COPY_TO_LOCAL),
                actionArg = "On phone"
            )
        )
        val plan = RuleEngine.evaluate(context(), rules)

        assertEquals(
            listOf(ActionType.COPY_TO_FOLDER, ActionType.COPY_TO_LOCAL),
            plan.copies.map { it.type }
        )
    }
}
