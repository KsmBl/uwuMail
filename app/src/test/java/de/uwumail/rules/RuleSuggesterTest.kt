package de.uwumail.rules

import de.uwumail.core.Json
import de.uwumail.core.RuleField
import de.uwumail.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSuggesterTest {

    private var nextId = 1L

    private fun message(
        subject: String,
        from: String,
        fromName: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
        to: String = "me@example.org"
    ) = MessageEntity(
        id = nextId++,
        accountId = 1,
        folderId = 1,
        uid = nextId,
        subject = subject,
        fromAddress = from,
        fromName = fromName,
        toList = to,
        headersJson = Json.encodeHeaders(headers)
    )

    /** Mail that looks like GitHub Actions failure notifications. */
    private fun ciMail(run: Int) = message(
        subject = "[myorg/backend] Run failed: CI #$run",
        from = "notifications@github.com",
        fromName = "myorg",
        headers = mapOf(
            "x-github-event" to listOf("workflow_run"),
            "list-id" to listOf("myorg/backend <backend.myorg.github.com>"),
            "precedence" to listOf("list")
        )
    )

    /** Ordinary GitHub mail that must NOT be swept up by a CI-failure rule. */
    private fun issueMail(number: Int) = message(
        subject = "[myorg/backend] New issue opened: #$number",
        from = "notifications@github.com",
        fromName = "myorg",
        headers = mapOf(
            "x-github-event" to listOf("issues"),
            "list-id" to listOf("myorg/backend <backend.myorg.github.com>"),
            "precedence" to listOf("list")
        )
    )

    private fun noise(index: Int) = message(
        subject = "Invoice $index for August",
        from = "billing@vendor.example",
        fromName = "Vendor Billing"
    )

    @Test
    fun `picks conditions that catch the selection and nothing else`() {
        val selected = listOf(ciMail(482), ciMail(1037), ciMail(7))
        val corpus = selected + (1..10).map { issueMail(it) } + (1..25).map { noise(it) }

        val report = RuleSuggester().analyse(selected, corpus)

        assertTrue("expected suggestions", report.suggestions.isNotEmpty())
        assertTrue("expected a recommended combination", report.recommended.isNotEmpty())
        assertEquals(
            "the recommendation must not catch anything outside the selection",
            0,
            report.recommendedOthersMatched
        )

        val conditions = report.recommended.map { report.suggestions[it].condition }
        val contexts = selected.map { MatchContext.of(it, "INBOX") }
        contexts.forEach { ctx ->
            assertTrue(
                "every selected mail must satisfy the recommendation",
                conditions.all { RuleMatcher.matches(it, ctx) }
            )
        }
        // The issue mails share the sender and List-Id, so a sender-only rule
        // would have been wrong; the suggester must have gone further.
        val issueContexts = (1..10).map { MatchContext.of(issueMail(it), "INBOX") }
        issueContexts.forEach { ctx ->
            assertTrue(
                "issue mail must not match the CI rule",
                !conditions.all { RuleMatcher.matches(it, ctx) }
            )
        }
    }

    @Test
    fun `surfaces the distinguishing header as a candidate`() {
        val selected = listOf(ciMail(1), ciMail(2), ciMail(3))
        val corpus = selected + (1..5).map { issueMail(it) }

        val report = RuleSuggester().analyse(selected, corpus)

        val headerCandidate = report.suggestions.firstOrNull {
            it.condition.field == RuleField.HEADER.name &&
                it.condition.headerName == "x-github-event" &&
                it.condition.value == "workflow_run"
        }
        assertTrue("expected the x-github-event header to be offered", headerCandidate != null)
        assertEquals(0, headerCandidate!!.othersMatched)
    }

    @Test
    fun `every candidate matches all selected messages`() {
        val selected = listOf(ciMail(11), ciMail(22))
        val report = RuleSuggester().analyse(selected, selected + (1..8).map { noise(it) })

        report.suggestions.forEach { suggestion ->
            assertEquals(
                "candidate ${suggestion.label} does not cover the whole selection",
                selected.size,
                suggestion.selectedMatched
            )
        }
    }

    @Test
    fun `reports precision against the corpus`() {
        val selected = listOf(ciMail(5))
        val corpus = selected + (1..3).map { issueMail(it) }
        val report = RuleSuggester().analyse(selected, corpus)

        val senderOnly = report.suggestions.first {
            it.condition.field == RuleField.FROM.name &&
                it.condition.operator == "EQUALS"
        }
        // Sender alone also hits the three issue mails; the UI shows exactly this.
        assertEquals(3, senderOnly.othersMatched)
        assertTrue(senderOnly.precision < 1.0)
    }

    @Test
    fun `handles an empty selection without blowing up`() {
        val report = RuleSuggester().analyse(emptyList(), listOf(noise(1)))
        assertTrue(report.suggestions.isEmpty())
        assertTrue(report.recommended.isEmpty())
    }
}
