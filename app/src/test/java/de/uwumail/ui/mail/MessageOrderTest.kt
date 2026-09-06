package de.uwumail.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageOrderTest {

    private fun order(vararg ids: Long) = MessageOrder().apply { publish(ids.toList()) }

    @Test
    fun `walks the list in the order it was shown`() {
        val order = order(10, 20, 30)
        assertEquals(20L, order.nextOf(10))
        assertEquals(30L, order.nextOf(20))
        assertEquals(10L, order.previousOf(20))
        assertEquals(20L, order.previousOf(30))
    }

    @Test
    fun `stops at both ends`() {
        val order = order(10, 20, 30)
        assertNull(order.previousOf(10))
        assertNull(order.nextOf(30))
    }

    @Test
    fun `a message that is not in the list has no neighbours`() {
        // Opened from a notification, with no list behind it.
        val order = order(10, 20)
        assertNull(order.nextOf(99))
        assertNull(order.previousOf(99))
    }

    @Test
    fun `nothing published means nothing to swipe to`() {
        val empty = MessageOrder()
        assertNull(empty.nextOf(1))
        assertNull(empty.previousOf(1))
    }

    @Test
    fun `a single message has nowhere to go`() {
        val order = order(7)
        assertNull(order.nextOf(7))
        assertNull(order.previousOf(7))
    }

    @Test
    fun `republishing the same order does not churn the flow`() {
        val order = MessageOrder()
        order.publish(listOf(1, 2, 3))
        val first = order.ids.value
        order.publish(listOf(1, 2, 3))
        // Same instance: collectors are not woken for a list that did not change.
        assertEquals(true, first === order.ids.value)
    }

    @Test
    fun `a new order replaces the old one`() {
        val order = MessageOrder()
        order.publish(listOf(1, 2, 3))
        order.publish(listOf(9, 8))
        assertEquals(8L, order.nextOf(9))
        assertNull(order.nextOf(2))
    }
}
