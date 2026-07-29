package dev.sam.wearsignal.messages

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Version counter bumped on every message-data write (inserts, reactions, seen marks,
 * attachment downloads, thread merges). Screens key their queries on [messagesVersion]
 * so data landed by background polls appears without a manual refresh.
 */
object DataChanges {

  private val version = MutableStateFlow(0L)

  val messagesVersion: StateFlow<Long> = version

  fun bumpMessages() {
    version.update { it + 1 }
  }
}
