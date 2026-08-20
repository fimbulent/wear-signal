package dev.sam.wearsignal.poll

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.calls.CallEngine
import dev.sam.wearsignal.calls.CallLog
import dev.sam.wearsignal.messages.GroupStateResolver
import dev.sam.wearsignal.messages.ProfileNameResolver
import dev.sam.wearsignal.tile.Glanceables
import org.signal.core.util.logging.Log
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState

/**
 * One poll cycle: drain the queue, then notify for new incoming messages
 * unless suppressed (phone connected, or an explicitly silent drain).
 */
object Poller {

  private val TAG = Log.tag(Poller::class)

  sealed interface Result {
    data class Success(val newMessages: Int) : Result
    data class Failure(val message: String, val unlinked: Boolean = false) : Result
  }

  /** Runs a drain. Never throws. Safe to call from any background thread. */
  fun poll(silent: Boolean = false): Result {
    if (!AppDeps.account.isLinked) {
      Log.w(TAG, "Not linked; skipping poll")
      return Result.Failure("Not linked")
    }
    if (CallEngine.isSessionActive) {
      // The call session's signaling loop is draining the queue already.
      return Result.Success(0)
    }

    val newMessages = try {
      AppDeps.retriever.drainQueue()
    } catch (t: Throwable) {
      Log.w(TAG, "Poll failed to drain the queue", t)
      // The server refusing our credentials means the account no longer knows this watch
      // (e.g. Signal was moved to a new phone, unlinking every device) — distinguish that
      // from a network problem so the UI can offer re-linking instead of "try again".
      val deregistered = AppDeps.net.authWebSocket.stateSnapshot == WebSocketConnectionState.AUTHENTICATION_FAILED ||
        generateSequence(t) { it.cause }.any { it is NonSuccessfulResponseCodeException && (it.code == 401 || it.code == 403) }
      try {
        AppDeps.net.authWebSocket.disconnect()
      } catch (_: Throwable) {
      }
      if (deregistered) {
        val firstDetection = !AppDeps.account.isDeregistered
        AppDeps.account.isDeregistered = true
        // Background polls stop while deregistered, so tell the user once why
        // notifications went quiet. In-app (silent) polls show the banner instead.
        if (!silent && firstDetection) {
          AppDeps.notifier.notifyUnlinked()
        }
        return Result.Failure("No longer linked", unlinked = true)
      }
      return Result.Failure("Couldn't connect")
    }

    if (AppDeps.account.isDeregistered) {
      // The credentials work again — the earlier rejection was transient. Withdraw the warning.
      AppDeps.account.isDeregistered = false
      AppDeps.notifier.cancelUnlinked()
    }

    // Take even on silent drains: they swallow missed-call alerts the way they swallow messages.
    val missedCalls = CallLog.takeUnnotifiedMissed()

    // Resolve names, group state, and photos for new senders and callers, plus any
    // contacts/groups still awaiting an avatar backfill (cheap no-op once fetched).
    val pendingAcis = newMessages.filterNot { it.fromSelf }.map { it.senderAci } +
      missedCalls.filterNot { it.isGroup }.map { it.peer } +
      ProfileNameResolver.pendingAvatarAcis()
    val pendingGroups = newMessages.mapNotNull { it.groupId } + GroupStateResolver.pendingAvatarGroupIds()
    if (pendingAcis.isNotEmpty() || pendingGroups.isNotEmpty()) {
      ProfileNameResolver.resolvePending(pendingAcis)
      GroupStateResolver.resolvePending(pendingGroups)
      // A fresh offer drained above may have started a call session that owns the socket.
      if (!CallEngine.isSessionActive) {
        AppDeps.net.authWebSocket.disconnect()
      }
    }

    // Contacts without a Signal profile photo fall back to their synced address-book photo.
    AppDeps.avatars.backfillDeviceContactPhotos()

    // Fetch pending image attachments (bounded per run) and apply retention.
    AppDeps.attachments.downloadPending()

    if (!silent) {
      // A read sync drained in this same batch may have already marked some of these seen
      // (read on the phone minutes ago) — notifying for those would be stale noise. Likewise
      // an edit in the same batch replaced the body we collected (a delete marks it seen).
      val unseen = newMessages.filterNot { it.fromSelf || AppDeps.messages.isSeen(it.senderAci, it.sentAt) }
        .map { it.copy(body = AppDeps.messages.currentBody(it.senderAci, it.sentAt) ?: it.body) }
      AppDeps.notifier.notify(unseen) { aci -> resolveName(aci) }
      AppDeps.notifier.notifyMissedCalls(missedCalls) { aci -> resolveName(aci) }
    }

    Glanceables.requestUpdate(AppDeps.context)

    return Result.Success(newMessages.size)
  }

  fun resolveName(aci: String): String {
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT name FROM contacts WHERE aci = ?",
      arrayOf(aci)
    ).use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) {
        return cursor.getString(0)
      }
    }
    return aci.take(8)
  }
}
