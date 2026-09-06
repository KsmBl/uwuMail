package de.uwumail.ui.mail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The messages the list is showing, in the order it is showing them.
 *
 * Swiping between messages has to follow what the user was just looking at, and
 * that order is not something a single message can work out for itself: it
 * depends on the folder or unified view they came from, and on any search they
 * had typed. So the list publishes it and the message screen reads it.
 *
 * It is deliberately not persisted. After the process has been killed and a
 * message reopened from a notification there is no list behind it, and swiping
 * simply does nothing until one has been seen.
 */
class MessageOrder {

    private val _ids = MutableStateFlow<List<Long>>(emptyList())
    val ids: StateFlow<List<Long>> = _ids.asStateFlow()

    fun publish(ids: List<Long>) {
        if (_ids.value != ids) _ids.value = ids
    }

    /** The message before [messageId] in the list, or null at either end. */
    fun previousOf(messageId: Long): Long? = neighbour(messageId, -1)

    fun nextOf(messageId: Long): Long? = neighbour(messageId, 1)

    private fun neighbour(messageId: Long, step: Int): Long? {
        val current = _ids.value
        val index = current.indexOf(messageId)
        if (index < 0) return null
        return current.getOrNull(index + step)
    }
}
