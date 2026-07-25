package dev.sam.wearsignal.calls

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Holds the authenticated websocket open for the duration of a call session and keeps
 * draining it, so answers/ICE/hangups reach the engine within seconds instead of at the
 * next poll. Regular messages arriving mid-call are stored like any drain.
 */
object CallSignaling {

  private val TAG = Log.tag(CallSignaling::class)
  private const val READ_TIMEOUT_MS = 2_000L
  private const val BATCH_SIZE = 16

  @Volatile
  private var running = false
  private var worker: Thread? = null

  @Synchronized
  fun start() {
    if (running) return
    running = true
    worker = thread(name = "call-signaling") { loop() }
  }

  /** Stops the loop; the worker disconnects on its way out (unless a call restarted). */
  @Synchronized
  fun stop() {
    running = false
  }

  private fun loop() {
    val webSocket = AppDeps.net.authWebSocket
    val processor = AppDeps.envelopeProcessor
    try {
      webSocket.connect()
      while (running) {
        try {
          webSocket.readMessageBatch(READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
            for (response in batch) {
              val message = processor.process(response.envelope, System.currentTimeMillis())
              if (message != null) {
                processor.store(message)
              }
              webSocket.sendAck(response)
            }
          }
        } catch (e: TimeoutException) {
          // Quiet socket. Reconnect if the connection dropped; otherwise keep listening.
          if (running && webSocket.stateSnapshot != WebSocketConnectionState.CONNECTED) {
            Log.w(TAG, "Signaling socket dropped (${webSocket.stateSnapshot}); reconnecting")
            webSocket.connect()
          }
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Signaling loop died", t)
    } finally {
      if (!running) {
        try {
          webSocket.disconnect()
        } catch (_: Throwable) {
        }
      }
    }
  }
}
