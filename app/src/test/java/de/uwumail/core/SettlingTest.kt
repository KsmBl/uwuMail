package de.uwumail.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettlingTest {

    @Test
    fun `only what the typing stopped on gets through`() = runTest {
        val typed = flow {
            emit("h")
            delay(20)
            emit("he")
            delay(20)
            emit("hel")
            delay(500)
            emit("hello")
        }

        assertEquals(listOf("hel", "hello"), typed.settled(200).toList())
    }

    @Test
    fun `a value that need not wait does not`() = runTest {
        val typed = flow {
            emit("a")
            delay(10)
            emit("")
        }

        val seen = typed.settled(1_000) { it.isBlank() }.toList()

        assertEquals(listOf(""), seen)
        // Had the empty one waited its turn as well, this would be past 1000.
        assertTrue(
            "cleared too late at ${testScheduler.currentTime}",
            testScheduler.currentTime < 1_000
        )
    }

    @Test
    fun `the same value twice is only one answer`() = runTest {
        val typed = flow {
            emit("mail")
            delay(500)
            emit("mail")
            delay(500)
            emit("mailbox")
        }

        assertEquals(listOf("mail", "mailbox"), typed.settled(100).toList())
    }
}
