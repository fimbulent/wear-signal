package dev.sam.wearsignal.calls

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.GroupCall
import org.signal.ringrtc.HttpHeader
import org.signal.ringrtc.NetworkRoute
import org.signal.ringrtc.PeekInfo
import org.signal.ringrtc.Remote
import org.whispersystems.signalservice.api.messages.calls.CallingResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The RingRTC bridge: owns the process-wide CallManager and services the callbacks
 * RingRTC needs regardless of call state (logging, its outbound HTTP for SFU peeks).
 * The 1:1 call state machine plugs in on top of this.
 */
object CallEngine : CallManager.Observer {

  private val TAG = Log.tag(CallEngine::class)

  @Volatile
  private var callManager: CallManager? = null

  /** RingRTC callbacks arrive on its worker thread; network work moves here. */
  private val httpExecutor = Executors.newSingleThreadExecutor()

  private val ringRtcLogger = object : org.signal.ringrtc.Log.Logger {
    override fun v(tag: String, message: String?, throwable: Throwable?) = Log.v(tag, message, throwable)
    override fun d(tag: String, message: String?, throwable: Throwable?) = Log.d(tag, message, throwable)
    override fun i(tag: String, message: String?, throwable: Throwable?) = Log.i(tag, message, throwable)
    override fun w(tag: String, message: String?, throwable: Throwable?) = Log.w(tag, message, throwable)
    override fun e(tag: String, message: String?, throwable: Throwable?) = Log.e(tag, message, throwable)
  }

  /** Lazily initializes RingRTC (loads the native library) and creates the CallManager. */
  @Synchronized
  fun manager(): CallManager {
    callManager?.let { return it }
    CallManager.initialize(AppDeps.context, ringRtcLogger, emptyMap())
    val created = CallManager.createCallManager(this) ?: throw IllegalStateException("createCallManager returned null")
    AppDeps.account.aci?.rawUuid?.let { created.setSelfUuid(it) }
    callManager = created
    return created
  }

  /** Asks the SFU who is in a group call. [handler] runs on RingRTC's worker thread. */
  fun peekGroupCall(
    sfuUrl: String,
    membershipProof: ByteArray,
    members: Collection<GroupCall.GroupMemberInfo>,
    handler: (PeekInfo) -> Unit
  ) {
    manager().peekGroupCall(sfuUrl, membershipProof, members) { info -> handler(info) }
  }

  // region CallManager.Observer

  /** RingRTC does its SFU HTTP through us; relay via the service socket and feed back. */
  override fun onSendHttpRequest(requestId: Long, url: String, method: CallManager.HttpMethod, headers: List<HttpHeader>?, body: ByteArray?) {
    httpExecutor.execute {
      val response = try {
        AppDeps.net.authPushServiceSocket.makeCallingRequest(
          requestId,
          url,
          method.name,
          headers?.map { Pair(it.name, it.value) } ?: emptyList(),
          body
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Calling HTTP request failed", t)
        null
      }
      try {
        when (response) {
          is CallingResponse.Success -> manager().receivedHttpResponse(requestId, response.responseStatus, response.responseBody ?: ByteArray(0))
          else -> manager().httpRequestFailed(requestId)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to hand HTTP response to RingRTC", t)
      }
    }
  }

  // The 1:1 call machinery (Phase 3). Until then these log and drop.

  override fun onStartCall(remote: Remote?, callId: CallId?, isOutgoing: Boolean?, callMediaType: CallManager.CallMediaType?) {
    Log.w(TAG, "onStartCall without call support; dropping")
  }

  override fun onCallEnded(remote: Remote?, reason: CallManager.CallEndReason, summary: CallSummary) {
    Log.i(TAG, "onCallEnded: $reason")
  }

  override fun onCallEvent(remote: Remote?, event: CallManager.CallEvent?) {
    Log.i(TAG, "onCallEvent: $event")
  }

  override fun onNetworkRouteChanged(remote: Remote?, networkRoute: NetworkRoute?) = Unit

  override fun onAudioLevels(remote: Remote?, capturedLevel: Int, receivedLevel: Int) = Unit

  override fun onLowBandwidthForVideo(remote: Remote?, recovered: Boolean) = Unit

  override fun onCallConcluded(remote: Remote?) = Unit

  override fun onSendOffer(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, opaque: ByteArray, callMediaType: CallManager.CallMediaType?) {
    Log.w(TAG, "onSendOffer without call support; dropping")
  }

  override fun onSendAnswer(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, opaque: ByteArray) {
    Log.w(TAG, "onSendAnswer without call support; dropping")
  }

  override fun onSendIceCandidates(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, iceCandidates: List<ByteArray>?) {
    Log.w(TAG, "onSendIceCandidates without call support; dropping")
  }

  override fun onSendHangup(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, hangupType: CallManager.HangupType?, deviceId: Int?) {
    Log.w(TAG, "onSendHangup without call support; dropping")
  }

  override fun onSendBusy(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?) {
    Log.w(TAG, "onSendBusy without call support; dropping")
  }

  override fun onSendCallMessage(recipientUuid: UUID, message: ByteArray, urgency: CallManager.CallMessageUrgency) {
    Log.w(TAG, "onSendCallMessage without call support; dropping")
  }

  override fun onSendCallMessageToGroup(groupId: ByteArray, message: ByteArray, urgency: CallManager.CallMessageUrgency, overrideRecipients: List<UUID>) {
    Log.w(TAG, "onSendCallMessageToGroup without group call support; dropping")
  }

  override fun onSendCallMessageToAdhocGroup(message: ByteArray, urgency: CallManager.CallMessageUrgency, expiration: Instant?, recipientsToEndorsements: Map<UUID, ByteArray>) = Unit

  override fun onGroupCallRingUpdate(groupId: ByteArray, ringId: Long, sender: UUID, update: CallManager.RingUpdate?) {
    Log.i(TAG, "onGroupCallRingUpdate: $update")
  }

  // endregion
}
