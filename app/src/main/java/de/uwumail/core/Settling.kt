package de.uwumail.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

/**
 * Passes a value on only once the source has stopped changing for [millis].
 *
 * Typing is the case this exists for: every letter of a search is a new value,
 * and answering each one means the database is asked six times for a six-letter
 * word, five of those answers being thrown away as the next letter lands.
 *
 * [immediately] names the values that should not wait — an emptied search box
 * has nothing to look up and should put the list back at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.settled(millis: Long, immediately: (T) -> Boolean = { false }): Flow<T> =
    distinctUntilChanged().mapLatest { value ->
        // mapLatest cancels this wait the moment another value arrives, which
        // is the whole mechanism: only a value nothing followed gets through.
        if (!immediately(value)) delay(millis)
        value
    }
