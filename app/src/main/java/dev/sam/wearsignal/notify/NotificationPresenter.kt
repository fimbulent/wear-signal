package dev.sam.wearsignal.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.sam.wearsignal.calls.CallLog
import dev.sam.wearsignal.messages.EnvelopeProcessor
import dev.sam.wearsignal.messages.GroupStateResolver
import dev.sam.wearsignal.messages.SeenMessage
import dev.sam.wearsignal.messages.attachmentPlaceholder
import dev.sam.wearsignal.ui.MainActivity
import org.signal.core.util.logging.Log

/**
 * Pops message notifications on the watch (used only when the phone is disconnected).
 */
class NotificationPresenter(private val context: Context) {

  companion object {
    private val TAG = Log.tag(NotificationPresenter::class)
    private const val CHANNEL_ID = "messages"
    private const val CALLS_CHANNEL_ID = "calls"
    private const val STATUS_CHANNEL_ID = "status"
    private val UNLINKED_NOTIFICATION_ID = "status:unlinked".hashCode()
    const val KEY_REPLY_TEXT = "reply_text"
    const val EXTRA_PEER = "peer"
    const val EXTRA_IS_GROUP = "is_group"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    /** Stable per-message id so a later read (local or synced) can cancel the notification. */
    fun messageNotificationId(senderAci: String, sentAt: Long): Int = "msg:$senderAci:$sentAt".hashCode()
  }

  init {
    val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
      enableVibration(true)
      description = "Signal messages received while away from phone"
    }
    val callsChannel = NotificationChannel(CALLS_CHANNEL_ID, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
      enableVibration(true)
      description = "Signal calls missed while away from phone"
    }
    val statusChannel = NotificationChannel(STATUS_CHANNEL_ID, "Account status", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Problems with the link to your Signal account"
    }
    context.getSystemService(NotificationManager::class.java).let {
      it.createNotificationChannel(channel)
      it.createNotificationChannel(callsChannel)
      it.createNotificationChannel(statusChannel)
    }
  }

  /** Posted once when the server rejects our credentials: the account unlinked this watch. */
  fun notifyUnlinked() {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Notification permission not granted")
      return
    }
    val contentIntent = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_warning)
      .setContentTitle("Watch unlinked")
      .setContentText("This watch is no longer linked to your Signal account. Open the app to re-link.")
      .setStyle(NotificationCompat.BigTextStyle().bigText("This watch is no longer linked to your Signal account. Open the app to re-link."))
      .setContentIntent(contentIntent)
      .setAutoCancel(true)
      .setCategory(NotificationCompat.CATEGORY_ERROR)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .build()
    NotificationManagerCompat.from(context).notify(UNLINKED_NOTIFICATION_ID, notification)
  }

  fun cancelUnlinked() {
    NotificationManagerCompat.from(context).cancel(UNLINKED_NOTIFICATION_ID)
  }

  /** Missed-call notifications, mirroring message semantics (only when the phone isn't covering us). */
  fun notifyMissedCalls(calls: List<CallLog.Entry>, nameResolver: (String) -> String) {
    if (calls.isEmpty()) return
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Notification permission not granted")
      return
    }

    val contentIntent = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val manager = NotificationManagerCompat.from(context)
    calls.takeLast(3).forEachIndexed { index, call ->
      val who = if (call.isGroup) GroupStateResolver.cachedTitle(call.peer) ?: "Group" else nameResolver(call.peer)
      val notification = NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_missed_call)
        .setContentTitle(if (call.isVideo) "Missed video call" else "Missed call")
        .setContentText(who)
        .setWhen(call.startedAt)
        .setShowWhen(true)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
      // Distinct id space from message notifications (those hash a "msg:" prefix),
      // so a call and a message with the same timestamp can't replace each other.
      manager.notify("call:${call.startedAt}:$index".hashCode(), notification)
    }
  }

  fun notify(messages: List<EnvelopeProcessor.IncomingMessage>, nameResolver: (String) -> String) {
    if (messages.isEmpty()) return
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Notification permission not granted")
      return
    }

    val manager = NotificationManagerCompat.from(context)

    messages.filterNot { it.fromSelf }.takeLast(5).forEach { message ->
      val notificationId = messageNotificationId(message.senderAci, message.sentAt)
      manager.notify(
        notificationId,
        buildMessageNotification(
          senderAci = message.senderAci,
          groupId = message.groupId,
          peer = message.peer,
          body = message.body,
          attachmentType = message.attachmentType,
          sentAt = message.sentAt,
          nameResolver = nameResolver
        )
      )
    }
  }

  /**
   * Refreshes a still-showing message notification after an edit replaced its text.
   * No-op when the notification was never posted or is already dismissed, so an edit
   * can't resurrect one.
   */
  fun updateMessageBody(
    senderAci: String,
    sentAt: Long,
    peer: String,
    groupId: String?,
    body: String,
    attachmentType: String?,
    nameResolver: (String) -> String
  ) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      return
    }
    val notificationId = messageNotificationId(senderAci, sentAt)
    val active = context.getSystemService(NotificationManager::class.java)
      .activeNotifications.any { it.id == notificationId }
    if (!active) return
    NotificationManagerCompat.from(context).notify(
      notificationId,
      buildMessageNotification(
        senderAci = senderAci,
        groupId = groupId,
        peer = peer,
        body = body,
        attachmentType = attachmentType,
        sentAt = sentAt,
        nameResolver = nameResolver
      )
    )
  }

  private fun buildMessageNotification(
    senderAci: String,
    groupId: String?,
    peer: String,
    body: String,
    attachmentType: String?,
    sentAt: Long,
    nameResolver: (String) -> String
  ): android.app.Notification {
    val contentIntent = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val sender = nameResolver(senderAci)
    val title = groupId
      ?.let { id -> GroupStateResolver.cachedTitle(id)?.let { "$sender @ $it" } ?: "$sender (group)" }
      ?: sender
    val notificationId = messageNotificationId(senderAci, sentAt)
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle(title)
      .setContentText(body.ifEmpty { attachmentPlaceholder(attachmentType) })
      .setWhen(sentAt)
      .setContentIntent(contentIntent)
      .setAutoCancel(true)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      // A body refresh after an edit must not buzz the wrist a second time.
      .setOnlyAlertOnce(true)
      // Wear's native reply (voice/keyboard/canned) via RemoteInput; groups fan out on send.
      .addAction(buildReplyAction(peer, groupId != null, notificationId))
      .build()
  }

  /** Retracts message notifications once they're read (thread opened here, or synced from the phone). */
  fun cancelMessages(messages: List<SeenMessage>) {
    if (messages.isEmpty()) return
    val manager = NotificationManagerCompat.from(context)
    for (message in messages) {
      manager.cancel(messageNotificationId(message.senderAci, message.sentAt))
    }
  }

  private fun buildReplyAction(peer: String, isGroup: Boolean, notificationId: Int): NotificationCompat.Action {
    val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
      .setLabel("Reply")
      .setAllowFreeFormInput(true)
      .build()

    val intent = Intent(context, ReplyReceiver::class.java).apply {
      putExtra(EXTRA_PEER, peer)
      putExtra(EXTRA_IS_GROUP, isGroup)
      putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      notificationId,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pendingIntent)
      .addRemoteInput(remoteInput)
      .setAllowGeneratedReplies(true)
      .build()
  }
}
