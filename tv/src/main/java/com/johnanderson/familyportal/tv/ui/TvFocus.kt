package com.johnanderson.familyportal.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Places a screen's initial focus once. Lazy-list items leave and re-enter
 * composition as they scroll, so "focus on first composition" alone would keep
 * pulling focus back to them; this remembers that focus has already been placed.
 */
@Stable
internal class InitialFocus {
    var placed by mutableStateOf(false)
}

/** A fresh [InitialFocus] whenever any of [keys] changes. */
@Composable
internal fun rememberInitialFocus(vararg keys: Any?): InitialFocus = remember(*keys) { InitialFocus() }

/** Requests focus for this element when it first appears, unless [focus] was already placed. */
@Composable
internal fun Modifier.initialFocus(focus: InitialFocus, enabled: Boolean = true): Modifier {
    if (!enabled || focus.placed) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (runCatching { requester.requestFocus() }.isSuccess) focus.placed = true
    }
    return focusRequester(requester)
}
