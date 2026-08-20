package dev.sam.wearsignal.link

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fasterxml.jackson.databind.ObjectMapper
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.messages.DataChanges
import dev.sam.wearsignal.messages.MessagesRepository
import okio.ByteString
import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.ChatItem
import org.signal.archive.proto.Contact
import org.signal.archive.proto.Frame
import org.signal.archive.proto.Group
import org.signal.core.models.ServiceId
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.core.util.readNBytesOrThrow
import org.signal.core.util.readVarInt32
import org.signal.core.util.stream.LimitedInputStream
import org.signal.core.util.stream.MacInputStream
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Link+sync history import. When the user picks "Transfer messages" while linking, the
 * provisioning message carries an ephemeral backup key; after this device registers, the
 * primary builds a backup archive of the message history, uploads it to the attachment CDN,
 * and announces it on GET /v1/devices/transfer_archive. This waits for that announcement,
 * downloads and decrypts the archive, and ingests it: contacts (names + profile keys),
 * groups (master keys + snapshots), and the newest messages of every conversation.
 *
 * The pending import (key, cutoff, and archive location once known) is persisted in
 * [dev.sam.wearsignal.account.AccountStore], so an import interrupted by sleep or process
 * death resumes on the next app open — ingestion is idempotent (existing rows are skipped).
 * A per-run trace is written to files/history-sync.log for post-mortems, since the watch's
 * logcat buffer rotates within minutes.
 *
 * Archive format (see Signal-Android's EncryptedBackupReader): an optional forward-secrecy
 * prefix (unused for link+sync), then HMAC-SHA256-authenticated AES-256-CBC data (IV first,
 * MAC over everything but its own trailing 32 bytes), containing a gzip stream of
 * varint-delimited protos: one BackupInfo, then Frames.
 */
object HistorySync {

  private val TAG = Log.tag(HistorySync::class)

  private const val POLL_STEP_SECONDS = 20 // must stay under the 30s socket read timeout
  private const val WAIT_TOTAL_MS = 10 * 60 * 1000L // big accounts take minutes to build+upload
  private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
  private const val MAX_ATTEMPTS = 5
  private const val MAC_SIZE = 32
  private const val IV_SIZE = 16
  private val MAGIC_NUMBER = "SBACKUP".toByteArray(Charsets.UTF_8) + 0x01

  data class Counts(val contacts: Int, val groups: Int, val conversations: Int, val messages: Int)

  sealed interface Result {
    data class Synced(val counts: Counts) : Result

    /** Nothing imported this run; [retryLater] means the pending state was kept for a resume. */
    data class Unavailable(val reason: String, val retryLater: Boolean) : Result
    data class Failed(val message: String) : Result
  }

  /** Whether an interrupted (or not-yet-run) import is waiting to be resumed. */
  val isPending: Boolean
    get() = AppDeps.account.pendingHistorySyncKey != null

  /**
   * Runs the pending import persisted at link time, if any. Blocking — call from a
   * background thread. Never throws. Clears the pending state on success, on the primary
   * reporting a failed upload, on user skip, and after [MAX_ATTEMPTS] failed tries;
   * transient failures keep it for the next call.
   */
  fun runPending(isCancelled: () -> Boolean = { false }, onStatus: (String) -> Unit = {}): Result {
    val account = AppDeps.account
    val key = account.pendingHistorySyncKey ?: return Result.Unavailable("No import pending", retryLater = false)

    val attempt = account.pendingHistorySyncAttempts + 1
    account.pendingHistorySyncAttempts = attempt

    val result = runOnce(key, account.pendingHistorySyncCutoff, isCancelled, onStatus)

    val keepPending = when (result) {
      is Result.Synced -> false
      is Result.Unavailable -> result.retryLater
      is Result.Failed -> true
    }
    if (!keepPending || attempt >= MAX_ATTEMPTS) {
      if (keepPending) {
        Log.w(TAG, "Giving up on history import after $attempt attempts")
        trace("giving up after $attempt attempts")
      }
      clearPending()
    }
    return result
  }

  private fun clearPending() {
    AppDeps.account.apply {
      pendingHistorySyncKey = null
      pendingHistorySyncArchive = null
      pendingHistorySyncCutoff = 0L
      pendingHistorySyncAttempts = 0
    }
  }

  private fun runOnce(
    ephemeralBackupKey: ByteArray,
    importCutoff: Long,
    isCancelled: () -> Boolean,
    onStatus: (String) -> Unit
  ): Result {
    traceFile().delete()
    trace("=== history sync started, cutoff=$importCutoff ===")
    val archive = File.createTempFile("transfer-archive", ".bin", AppDeps.context.cacheDir)
    try {
      onStatus("Waiting for your phone…")
      val location = when (val wait = waitForArchive(isCancelled)) {
        is Wait.Found -> wait
        Wait.PrimaryFailed -> {
          trace("primary reported an upload error; not retrying")
          return Result.Unavailable("The phone could not upload the history", retryLater = false)
        }
        Wait.Skipped -> {
          trace("skipped by user")
          return Result.Unavailable("Skipped", retryLater = false)
        }
        Wait.TimedOut -> {
          trace("timed out waiting for the archive announcement")
          return Result.Unavailable("The phone has not finished uploading yet", retryLater = true)
        }
      }
      trace("archive announced: cdn=${location.cdn} key=${location.key.take(16)}…")

      onStatus("Downloading history…")
      AppDeps.net.authPushServiceSocket.retrieveAttachment(
        location.cdn,
        emptyMap(),
        SignalServiceAttachmentRemoteId.V4(location.key),
        archive,
        MAX_ARCHIVE_BYTES,
        null
      )
      trace("downloaded ${archive.length()} bytes")
      if (isCancelled()) {
        trace("skipped by user after download")
        return Result.Unavailable("Skipped", retryLater = false)
      }

      onStatus("Importing history…")
      val aci = AppDeps.account.aci ?: return Result.Failed("Not linked")
      val material = MessageBackupKey(ephemeralBackupKey).deriveBackupSecrets(aci, forwardSecrecyToken = null)

      val ingester = Ingester(selfAci = aci.toString(), importCutoff = importCutoff)
      readFrames(archive, material) { frame -> ingester.accept(frame) }
      val counts = ingester.commit()
      trace("ingested: $counts, frame stats: ${ingester.stats()}")
      DataChanges.bumpMessages()
      Log.i(TAG, "History sync complete: $counts")
      trace("=== success ===")
      return Result.Synced(counts)
    } catch (t: Throwable) {
      Log.w(TAG, "History sync failed", t)
      trace("FAILED: ${StringWriter().also { t.printStackTrace(PrintWriter(it)) }}")
      return Result.Failed(t.message ?: "History sync failed")
    } finally {
      archive.delete()
    }
  }

  private sealed interface Wait {
    class Found(val cdn: Int, val key: String) : Wait
    data object PrimaryFailed : Wait
    data object TimedOut : Wait
    data object Skipped : Wait
  }

  /**
   * Returns the archive location: the one persisted by an earlier interrupted run, or the
   * result of long-polling /v1/devices/transfer_archive until the primary announces the
   * upload, reports an error, the user skips, or [WAIT_TOTAL_MS] passes.
   */
  private fun waitForArchive(isCancelled: () -> Boolean): Wait {
    AppDeps.account.pendingHistorySyncArchive?.let { persisted ->
      val cdn = persisted.substringBefore(':').toIntOrNull()
      val key = persisted.substringAfter(':')
      if (cdn != null && key.isNotEmpty()) {
        trace("using persisted archive location")
        return Wait.Found(cdn, key)
      }
    }

    val deadline = System.currentTimeMillis() + WAIT_TOTAL_MS
    while (System.currentTimeMillis() < deadline) {
      if (isCancelled()) return Wait.Skipped
      val json = try {
        AppDeps.net.authPushServiceSocket.waitForTransferArchive(POLL_STEP_SECONDS) ?: continue
      } catch (e: NonSuccessfulResponseCodeException) {
        if (e.code == 429) {
          trace("rate limited; backing off")
          Thread.sleep(10_000)
          continue
        }
        throw e
      }
      if (json.isBlank()) continue
      trace("transfer_archive response: ${json.take(200)}")

      val node = ObjectMapper().readTree(json)
      if (node.get("error") != null) {
        Log.w(TAG, "Primary reported a transfer archive error: ${node.get("error").asText()}")
        return Wait.PrimaryFailed
      }
      val cdn = node.get("cdn")?.asInt() ?: continue
      val key = node.get("key")?.asText() ?: continue
      // Persist so an interrupted download/import can resume without re-polling.
      AppDeps.account.pendingHistorySyncArchive = "$cdn:$key"
      return Wait.Found(cdn, key)
    }
    return Wait.TimedOut
  }

  /** Authenticates, decrypts, and gunzips the archive, invoking [onFrame] per frame. */
  private fun readFrames(file: File, material: MessageBackupKey.BackupKeyMaterial, onFrame: (Frame) -> Unit) {
    val prefixLength = file.inputStream().buffered().use { forwardSecrecyPrefixLength(it) }
    val encryptedLength = file.length() - prefixLength
    if (encryptedLength < IV_SIZE + MAC_SIZE + 1) {
      throw IOException("Archive too small: ${file.length()} bytes (prefix $prefixLength)")
    }

    // Pass 1: authenticate. The MAC covers everything after the prefix except its own 32 bytes.
    file.inputStream().buffered().use { stream ->
      stream.skipFully(prefixLength)
      val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(material.macKey, "HmacSHA256")) }
      val macStream = MacInputStream(LimitedInputStream(stream, encryptedLength - MAC_SIZE), mac)
      val scratch = ByteArray(64 * 1024)
      while (macStream.read(scratch) >= 0) {
        // reading for the MAC side effect only
      }
      val calculated = macStream.mac.doFinal()
      val expected = stream.readNBytesOrThrow(MAC_SIZE)
      if (!MessageDigest.isEqual(calculated, expected)) {
        throw IOException("Transfer archive failed the MAC check")
      }
    }
    trace("MAC verified over ${encryptedLength - MAC_SIZE} bytes")

    // Pass 2: decrypt and parse.
    file.inputStream().buffered().use { raw ->
      raw.skipFully(prefixLength)
      val iv = raw.readNBytesOrThrow(IV_SIZE)
      val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(material.aesKey, "AES"), IvParameterSpec(iv))
      }
      val plain = GZIPInputStream(
        CipherInputStream(LimitedInputStream(raw, encryptedLength - IV_SIZE - MAC_SIZE), cipher)
      )

      val headerLength = plain.readVarInt32().takeIf { it >= 0 } ?: throw IOException("Archive has no header")
      val header = BackupInfo.ADAPTER.decode(plain.readNBytesOrThrow(headerLength))
      Log.i(TAG, "Reading transfer archive: version=${header.version}, from app ${header.currentAppVersion}")
      trace("archive header: version=${header.version}, app=${header.currentAppVersion}")

      var undecodable = 0
      while (true) {
        val length = plain.readVarInt32().takeIf { it >= 0 } ?: break
        val frameBytes = try {
          plain.readNBytesOrThrow(length)
        } catch (e: EOFException) {
          break
        }
        val frame = try {
          Frame.ADAPTER.decode(frameBytes)
        } catch (e: IOException) {
          undecodable++ // skip undecodable frames rather than losing the rest
          continue
        }
        onFrame(frame)
      }
      if (undecodable > 0) trace("$undecodable frame(s) failed to decode")
    }
  }

  /**
   * Newer archives may start with a magic-number-prefixed forward-secrecy metadata block
   * (unused without a forward secrecy token). Returns its total length, or 0 if absent.
   */
  private fun forwardSecrecyPrefixLength(stream: InputStream): Long {
    val head = ByteArray(MAGIC_NUMBER.size)
    var read = 0
    while (read < head.size) {
      val n = stream.read(head, read, head.size - read)
      if (n < 0) return 0
      read += n
    }
    if (!head.contentEquals(MAGIC_NUMBER)) return 0
    val length = stream.readVarInt32()
    if (length < 0 || length > 64 * 1024) throw IOException("Bad forward-secrecy metadata length: $length")
    return MAGIC_NUMBER.size.toLong() + varIntLength(length) + length
  }

  private fun varIntLength(value: Int): Int {
    var v = value
    var n = 1
    while (v and 0x7F.inv() != 0) {
      v = v ushr 7
      n++
    }
    return n
  }

  private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
      val skipped = skip(remaining)
      if (skipped <= 0) {
        if (read() < 0) throw EOFException("Hit EOF while skipping")
        remaining--
      } else {
        remaining -= skipped
      }
    }
  }

  private fun traceFile(): File = File(AppDeps.context.filesDir, "history-sync.log")

  /** Appends to files/history-sync.log — logcat rotates too fast on the watch to rely on. */
  private fun trace(message: String) {
    try {
      val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
      traceFile().appendText("$stamp $message\n")
    } catch (_: Throwable) {
    }
  }

  /**
   * Maps frames onto the watch schema. Frame ordering is guaranteed by the format: a frame
   * referenced by id always precedes its referencers, so recipients arrive before chats and
   * chats before chat items — one pass suffices. Recipients and groups are written as they
   * stream by; messages are held (newest [KEEP_PER_CONVERSATION] per chat) and committed
   * per conversation at the end, so an interruption keeps whole conversations.
   */
  private class Ingester(private val selfAci: String, private val importCutoff: Long) {

    companion object {
      private const val KEEP_PER_CONVERSATION = MessagesRepository.MAX_PER_CONVERSATION
    }

    private sealed interface Dest
    private data class ContactDest(val peer: String) : Dest
    private data class GroupDest(val groupId: String) : Dest
    private data object SelfDest : Dest

    private val destinations = HashMap<Long, Dest>()
    private val chats = HashMap<Long, Dest>()

    private class PendingReaction(val emoji: String, val reacterAci: String, val sortOrder: Long)

    private class PendingMessage(
      val senderAci: String,
      val groupId: String?,
      val body: String,
      val sentAt: Long,
      val serverAt: Long,
      val fromSelf: Boolean,
      val deliveredAt: Long,
      val readAt: Long,
      val seenAt: Long,
      val revisedAt: Long,
      val remoteDeleted: Boolean,
      val attachmentType: String?,
      val attachmentPointer: ByteArray?,
      val reactions: List<PendingReaction>
    )

    private val kept = HashMap<String, MutableList<PendingMessage>>()
    private var contacts = 0
    private var groups = 0
    private val skips = HashMap<String, Int>()
    private var frames = 0
    private var chatItems = 0
    private val db = AppDeps.database.writableDatabase
    private val now = System.currentTimeMillis()

    fun stats(): String =
      "frames=$frames chatItems=$chatItems held=${kept.values.sumOf { it.size }} skips=$skips"

    private fun skip(reason: String) {
      skips[reason] = (skips[reason] ?: 0) + 1
    }

    fun accept(frame: Frame) {
      frames++
      val recipient = frame.recipient
      if (recipient != null) {
        val contact = recipient.contact
        val group = recipient.group
        val dest = when {
          contact != null -> acceptContact(contact)
          group != null -> acceptGroup(group)
          recipient.self != null -> SelfDest
          else -> null // distribution lists, call links, release notes: not conversations here
        }
        if (dest != null) destinations[recipient.id] = dest
        return
      }

      val chat = frame.chat
      if (chat != null) {
        chats[chat.id] = destinations[chat.recipientId] ?: return
        return
      }

      frame.chatItem?.let { acceptChatItem(it) }
    }

    private fun acceptContact(contact: Contact): Dest? {
      val aci = ServiceId.ACI.parseOrNull(contact.aci)
      val peer = aci?.toString() ?: pniFromBytes(contact.pni)?.toString() ?: return null

      val systemName = "${contact.systemGivenName} ${contact.systemFamilyName}".trim()
      val profileName = "${contact.profileGivenName.orEmpty()} ${contact.profileFamilyName.orEmpty()}".trim()
      val name = systemName.ifEmpty { profileName }
        .ifEmpty { contact.username.orEmpty() }
        .ifEmpty { contact.e164?.let { "+$it" }.orEmpty() }
        .ifEmpty { null }

      if (aci != null) {
        val values = ContentValues().apply {
          put("aci", peer)
          put("profile_key", contact.profileKey?.toByteArray())
          put("name", name)
          // A known name marks the row fresh so the resolver doesn't refetch it; the photo
          // still backfills because avatar_fetched_at stays 0.
          put("fetched_at", if (name != null) now else 0L)
        }
        if (db.insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
          db.execSQL(
            "UPDATE contacts SET profile_key = COALESCE(profile_key, ?), name = COALESCE(name, ?) WHERE aci = ?",
            arrayOf(contact.profileKey?.toByteArray(), name, peer)
          )
        }
        contacts++
        contact.e164?.let { e164 ->
          db.execSQL(
            "INSERT OR REPLACE INTO directory (e164, aci, name) VALUES (?, ?, ?)",
            arrayOf("+$e164", peer, name)
          )
        }
      }
      return ContactDest(peer)
    }

    private fun acceptGroup(group: Group): Dest? {
      val masterKey = group.masterKey.toByteArray()
      val groupId = try {
        val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
        Base64.encodeWithPadding(secretParams.publicParams.groupIdentifier.serialize())
      } catch (t: Throwable) {
        skip("badGroupKey")
        return null
      }

      val snapshot = group.snapshot
      val title = snapshot?.title?.title?.trim()?.takeIf { it.isNotEmpty() }
      val members = snapshot?.members.orEmpty()
        .mapNotNull { ServiceId.ACI.parseOrNull(it.userId)?.toString() }
        .joinToString(",")

      val values = ContentValues().apply {
        put("group_id", groupId)
        put("master_key", masterKey)
        put("revision", snapshot?.version ?: 0)
        if (title != null) put("title", title)
        if (members.isNotEmpty()) put("members", members)
        // With title+members from the snapshot the state is usable as-is; the photo still
        // backfills via avatar_fetched_at = 0, which refreshes the rest from the server too.
        if (title != null && members.isNotEmpty()) put("fetched_at", now)
      }
      if (db.insertWithOnConflict("groups", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
        db.execSQL(
          "UPDATE groups SET title = COALESCE(?, title), members = COALESCE(?, members), revision = MAX(revision, ?) WHERE group_id = ?",
          arrayOf(title, members.ifEmpty { null }, snapshot?.version ?: 0, groupId)
        )
      }
      groups++
      return GroupDest(groupId)
    }

    private fun acceptChatItem(item: ChatItem) {
      chatItems++
      val dest = chats[item.chatId] ?: return skip("unknownChat")

      val author = destinations[item.authorId]
      val fromSelf = author == SelfDest || item.outgoing != null
      val senderAci = when {
        author is ContactDest && !fromSelf -> author.peer
        fromSelf -> selfAci
        else -> return skip("unknownAuthor")
      }

      // For an edited message the top-level item is the newest revision and `revisions`
      // holds the older ones (oldest first) — the original sent timestamp keys the row,
      // matching how receipts and reactions resolve messages elsewhere in the app.
      val originalSentAt = item.revisions.firstOrNull()?.dateSent ?: item.dateSent
      if (originalSentAt >= importCutoff) return skip("afterCutoff") // the first drain delivers these
      val revisedAt = if (item.revisions.isNotEmpty()) item.dateSent else 0L

      val incoming = item.incoming
      val outgoing = item.outgoing
      if (incoming == null && outgoing == null) return skip("directionless") // chat updates etc.

      val standard = item.standardMessage
      val remoteDeleted = item.remoteDeletedMessage != null
      if (standard == null && !remoteDeleted) return skip("unsupportedType") // stickers, payments, polls, …

      val body = if (remoteDeleted) "" else standard?.text?.body.orEmpty()
      var attachmentType: String? = null
      var attachmentPointer: ByteArray? = null
      if (!remoteDeleted) {
        val pointer = standard?.attachments?.firstOrNull()?.pointer
        val locator = pointer?.locatorInfo
        attachmentType = pointer?.contentType
        if (locator != null && locator.transitCdnKey != null && locator.key.size > 0) {
          // Rebuild the wire AttachmentPointer the existing downloader consumes. Only
          // transit-CDN copies are reachable from a linked device; anything else renders
          // as the usual placeholder.
          attachmentPointer = AttachmentPointer(
            cdnKey = locator.transitCdnKey,
            cdnNumber = locator.transitCdnNumber ?: 0,
            key = locator.key,
            digest = locator.encryptedDigest,
            size = locator.size,
            contentType = pointer.contentType,
            uploadTimestamp = locator.transitTierUploadTimestamp ?: 0
          ).encode()
        }
      }
      if (body.isEmpty() && attachmentType == null && !remoteDeleted) return skip("emptyBody")

      var deliveredAt = 0L
      var readAt = 0L
      if (outgoing != null) {
        for (status in outgoing.sendStatus) {
          if (status.read != null || status.viewed != null) readAt = maxOf(readAt, status.timestamp)
          if (status.delivered != null) deliveredAt = maxOf(deliveredAt, status.timestamp)
        }
        if (readAt > 0 && deliveredAt == 0L) deliveredAt = readAt
      }

      val reactions = (if (remoteDeleted) emptyList() else standard?.reactions.orEmpty()).mapNotNull { reaction ->
        val reacterAci = when (val reacter = destinations[reaction.authorId]) {
          is ContactDest -> reacter.peer
          SelfDest -> selfAci
          else -> return@mapNotNull null
        }
        PendingReaction(reaction.emoji, reacterAci, reaction.sortOrder)
      }

      val (peer, groupId) = when (dest) {
        is GroupDest -> dest.groupId to dest.groupId
        is ContactDest -> dest.peer to null
        SelfDest -> selfAci to null // note-to-self
      }

      val list = kept.getOrPut(peer) { mutableListOf() }
      list += PendingMessage(
        senderAci = senderAci,
        groupId = groupId,
        body = body,
        sentAt = originalSentAt,
        serverAt = incoming?.dateReceived ?: outgoing?.dateReceived ?: item.dateSent,
        fromSelf = fromSelf,
        deliveredAt = deliveredAt,
        readAt = readAt,
        seenAt = if (incoming != null && incoming.read) now else 0L,
        revisedAt = revisedAt,
        remoteDeleted = remoteDeleted,
        attachmentType = attachmentType,
        attachmentPointer = attachmentPointer,
        reactions = reactions
      )
      // Compact occasionally so an all-history archive can't grow the heap unbounded.
      if (list.size > KEEP_PER_CONVERSATION * 2) {
        list.sortByDescending { it.sentAt }
        list.subList(KEEP_PER_CONVERSATION, list.size).clear()
      }
    }

    /**
     * Writes the kept messages (and their reactions), one transaction per conversation so
     * an interruption mid-commit keeps the conversations already written.
     */
    fun commit(): Counts {
      var inserted = 0
      for ((peer, list) in kept) {
        list.sortByDescending { it.sentAt }
        db.beginTransaction()
        try {
          for (message in list.take(KEEP_PER_CONVERSATION)) {
            // Relinking re-imports history the watch may already hold — skip those rows.
            val exists = db.rawQuery(
              "SELECT 1 FROM messages WHERE peer = ? AND sent_at = ? AND sender_aci = ? LIMIT 1",
              arrayOf(peer, message.sentAt.toString(), message.senderAci)
            ).use { it.moveToFirst() }
            if (exists) {
              skip("alreadyPresent")
              continue
            }

            val values = ContentValues().apply {
              put("peer", peer)
              put("sender_aci", message.senderAci)
              put("group_id", message.groupId)
              put("body", message.body)
              put("sent_at", message.sentAt)
              put("server_at", message.serverAt)
              put("from_self", if (message.fromSelf) 1 else 0)
              put("delivered_at", message.deliveredAt)
              put("read_at", message.readAt)
              put("seen_at", message.seenAt)
              put("revised_at", message.revisedAt)
              put("remote_deleted", if (message.remoteDeleted) 1 else 0)
              put("attachment_type", message.attachmentType)
              put("attachment_pointer", message.attachmentPointer)
            }
            db.insert("messages", null, values)
            inserted++

            for (reaction in message.reactions) {
              val reactionValues = ContentValues().apply {
                put("peer", peer)
                put("target_sent_at", message.sentAt)
                put("target_author_aci", message.senderAci)
                put("reacter_aci", reaction.reacterAci)
                put("emoji", reaction.emoji)
                put("at", reaction.sortOrder)
              }
              db.insertWithOnConflict("reactions", null, reactionValues, SQLiteDatabase.CONFLICT_REPLACE)
            }
          }
          db.setTransactionSuccessful()
        } finally {
          db.endTransaction()
        }
      }
      return Counts(contacts = contacts, groups = groups, conversations = kept.size, messages = inserted)
    }

    private fun pniFromBytes(bytes: ByteString?): ServiceId.PNI? {
      val raw = bytes?.toByteArray() ?: return null
      if (raw.size != 16) return null
      val buffer = ByteBuffer.wrap(raw)
      return ServiceId.PNI.from(UUID(buffer.long, buffer.long))
    }
  }
}
