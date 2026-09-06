package de.uwumail.rules

import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.RuleActionEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.data.db.RuleWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conditions on an attachment's filename.
 *
 * The field was offered in the editor and documented, and every context the
 * engine was ever handed carried an empty list of names — so the condition was
 * always false and the rule quietly never fired.
 */
class AttachmentRuleTest {

    private fun message(subject: String = "Scan") = MessageEntity(
        accountId = 1,
        folderId = 1,
        uid = 1,
        subject = subject,
        fromAddress = "scanner@office.example"
    )

    private fun context(vararg names: String) =
        MatchContext.of(message(), "INBOX", attachmentNames = names.toList())

    private fun rule(
        value: String,
        operator: RuleOperator = RuleOperator.ENDS_WITH,
        negate: Boolean = false
    ) = RuleWithDetails(
        rule = RuleEntity(id = 1, name = "Invoices", matchMode = MatchMode.ALL.name),
        conditions = listOf(
            RuleConditionEntity(
                ruleId = 1,
                field = RuleField.ATTACHMENT_NAME.name,
                operator = operator.name,
                value = value,
                negate = negate
            )
        ),
        actions = listOf(
            RuleActionEntity(ruleId = 1, type = ActionType.FLAG.name, orderIndex = 0)
        )
    )

    @Test
    fun `a rule on an attachment name matches the mail carrying it`() {
        val plan = RuleEngine.evaluate(context("invoice-4711.pdf"), listOf(rule(".pdf")))

        assertTrue(plan.matched)
        assertEquals(true, plan.flag)
    }

    @Test
    fun `a mail with no attachments does not match`() {
        assertFalse(RuleEngine.evaluate(context(), listOf(rule(".pdf"))).matched)
    }

    @Test
    fun `one attachment out of several is enough`() {
        val plan = RuleEngine.evaluate(
            context("notes.txt", "photo.jpg", "invoice.pdf"),
            listOf(rule(".pdf"))
        )

        assertTrue(plan.matched)
    }

    @Test
    fun `a name that does not match leaves the rule alone`() {
        assertFalse(RuleEngine.evaluate(context("photo.jpg"), listOf(rule(".pdf"))).matched)
    }

    @Test
    fun `contains reaches into the middle of a name`() {
        val plan = RuleEngine.evaluate(
            context("2026-invoice-final.pdf"),
            listOf(rule("invoice", RuleOperator.CONTAINS))
        )

        assertTrue(plan.matched)
    }

    @Test
    fun `an inverted condition catches the mail without that attachment`() {
        assertTrue(
            RuleEngine.evaluate(context("photo.jpg"), listOf(rule(".pdf", negate = true))).matched
        )
    }

    @Test
    fun `matching ignores case unless it is asked not to`() {
        val plan = RuleEngine.evaluate(context("INVOICE.PDF"), listOf(rule(".pdf")))

        assertTrue(plan.matched)
    }

    @Test
    fun `the field says it needs the message structure`() {
        assertTrue(RuleField.ATTACHMENT_NAME.needsAttachmentNames)
        assertFalse(RuleField.SUBJECT.needsAttachmentNames)
        assertFalse(RuleField.ATTACHMENT_NAME.needsBody)
    }
}
