package de.uwumail.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The order rules run in, and copying one.
 *
 * Order decides behaviour — an early rule that stops processing means a later
 * one never runs — and it was neither shown nor changeable, so a rule that
 * "did not work" had no visible explanation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var rules: RuleDao

    @Before
    fun open() {
        db = TestDatabase.open()
        rules = db.ruleDao()
    }

    @After
    fun close() = db.close()

    private suspend fun rule(name: String, priority: Int = 100): Long =
        rules.insertRule(RuleEntity(name = name, priority = priority))

    private suspend fun order(): List<String> =
        rules.observeAll().first().map { it.rule.name }

    @Test
    fun `moving a rule up changes the order it runs in`() = runBlocking {
        rule("first", 0); rule("second", 1); rule("third", 2)

        val current = rules.observeAll().first().map { it.rule }.toMutableList()
        current.add(0, current.removeAt(2))
        rules.renumber(current)

        assertEquals(listOf("third", "first", "second"), order())
    }

    @Test
    fun `moving a rule down changes it the other way`() = runBlocking {
        rule("first", 0); rule("second", 1); rule("third", 2)

        val current = rules.observeAll().first().map { it.rule }.toMutableList()
        current.add(2, current.removeAt(0))
        rules.renumber(current)

        assertEquals(listOf("second", "third", "first"), order())
    }

    @Test
    fun `renumbering gives every rule a place of its own`() = runBlocking {
        // Rules written by earlier versions all share the default priority,
        // which leaves their order undefined.
        rule("a", 100); rule("b", 100); rule("c", 100)

        rules.renumber(rules.observeAll().first().map { it.rule })

        assertEquals(listOf(0, 1, 2), rules.observeAll().first().map { it.rule.priority })
    }

    @Test
    fun `an order already correct is left alone`() = runBlocking {
        rule("first", 0); rule("second", 1)

        rules.renumber(rules.observeAll().first().map { it.rule })

        assertEquals(listOf("first", "second"), order())
    }

    @Test
    fun `a duplicate carries the conditions and actions with it`() = runBlocking {
        val id = rule("Invoices")
        rules.insertConditions(
            listOf(
                RuleConditionEntity(
                    ruleId = id, field = "FROM", operator = "CONTAINS", value = "billing@"
                )
            )
        )
        rules.insertActions(
            listOf(RuleActionEntity(ruleId = id, type = "FLAG", orderIndex = 0))
        )

        val copyId = rules.duplicate(rules.get(id)!!, "(copy)")

        val copy = rules.get(copyId)!!
        assertEquals(1, copy.conditions.size)
        assertEquals("billing@", copy.conditions.first().value)
        assertEquals(1, copy.actions.size)
    }

    @Test
    fun `a duplicate is named apart from its original`() = runBlocking {
        val id = rule("Invoices")

        val copy = rules.get(rules.duplicate(rules.get(id)!!, "(copy)"))!!

        assertEquals("Invoices (copy)", copy.rule.name)
    }

    @Test
    fun `a duplicate starts switched off`() = runBlocking {
        // Two identical rules both running is not what "duplicate" means.
        val id = rule("Invoices")

        assertFalse(rules.get(rules.duplicate(rules.get(id)!!, "(copy)"))!!.rule.enabled)
    }

    @Test
    fun `a duplicate does not inherit the original's history`() = runBlocking {
        val id = rule("Invoices")
        rules.recordMatch(id, at = 12345L)

        val copy = rules.get(rules.duplicate(rules.get(id)!!, "(copy)"))!!

        assertEquals(0, copy.rule.matchCount)
        assertEquals(null, copy.rule.lastMatchedAt)
    }

    @Test
    fun `the original is untouched by being copied`() = runBlocking {
        val id = rule("Invoices")

        rules.duplicate(rules.get(id)!!, "(copy)")

        val original = rules.get(id)!!
        assertEquals("Invoices", original.rule.name)
        assertTrue(original.rule.enabled)
    }
}
