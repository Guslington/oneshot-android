package com.oneshotchat.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

/** Small alias so screens read clearly; backed by Compose's collectAsState. */
@Composable
fun <T> StateFlow<T>.collectAsStateLifecycle(): State<T> = collectAsState()
