package cl.digitalmonster.opencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/** Estado del directo, visible para la pantalla principal. */
object StreamStatus {
  enum class State { IDLE, CONNECTING, LIVE, RECONNECTING, ERROR }

  data class Snapshot(val state: State, val message: String = "", val bitrate: Long = 0, val startedAt: Long = 0)

  @Volatile var current = Snapshot(State.IDLE)
    private set

  private val main = Handler(Looper.getMainLooper())
  private var listener: ((Snapshot) -> Unit)? = null

  fun observe(l: ((Snapshot) -> Unit)?) {
    listener = l
    l?.invoke(current)
  }

  fun post(s: Snapshot) {
    current = s
    main.post { listener?.invoke(s) }
  }
}

/**
 * Servicio en primer plano que captura la pantalla (o una sola app en Android 14+),
 * mezcla el audio y lo envía por RTMP/RTMPS. Sigue funcionando mientras juegas.
 */
class StreamService : Service(), ConnectChecker {

  private var stream: GenericStream? = null
  private var projection: MediaProjection? = null
  private val handler = Handler(Looper.getMainLooper())

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> start(intent)
      ACTION_STOP -> shutdown(StreamStatus.Snapshot(StreamStatus.State.IDLE))
    }
    return START_NOT_STICKY
  }

  private fun start(intent: Intent) {
    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
    val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
    val endpoint = intent.getStringExtra(EXTRA_ENDPOINT)
    val quality = Quality.fromId(intent.getStringExtra(EXTRA_QUALITY))
    val audioMode = AudioMode.fromId(intent.getStringExtra(EXTRA_AUDIO))

    // Android exige mostrar la notificación ANTES de pedir la captura de pantalla.
    startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_connecting)), fgsTypes())

    if (data == null || endpoint == null) {
      shutdown(StreamStatus.Snapshot(StreamStatus.State.ERROR, getString(R.string.error_missing_data)))
      return
    }

    try {
      val manager = getSystemService(MediaProjectionManager::class.java)
      val mp = manager.getMediaProjection(resultCode, data)
        ?: throw IllegalStateException("MediaProjection nula")
      projection = mp

      val onProjectionStop = object : MediaProjection.Callback() {
        override fun onStop() {
          // El usuario detuvo la captura desde el sistema.
          handler.post { shutdown(StreamStatus.Snapshot(StreamStatus.State.IDLE)) }
        }
      }

      val audio: AudioSource = when (audioMode) {
        AudioMode.MIC -> MicrophoneSource()
        AudioMode.GAME_AND_MIC -> MixAudioSource(mp)
      }

      val s = GenericStream(this, this, ScreenSource(applicationContext, mp, onProjectionStop), audio).apply {
        // La captura solo genera cuadros cuando la pantalla cambia; esto mantiene los FPS estables.
        getGlInterface().setForceRender(true, FPS)
        getStreamClient().setReTries(RETRIES)
      }

      val prepared = s.prepareVideo(quality.width, quality.height, quality.bitrate, fps = FPS, rotation = 0) &&
        s.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE, echoCanceler = audioMode == AudioMode.MIC)

      if (!prepared) {
        s.release()
        shutdown(StreamStatus.Snapshot(StreamStatus.State.ERROR, getString(R.string.error_prepare)))
        return
      }

      stream = s
      StreamStatus.post(StreamStatus.Snapshot(StreamStatus.State.CONNECTING))
      s.startStream(endpoint)
    } catch (e: Exception) {
      shutdown(StreamStatus.Snapshot(StreamStatus.State.ERROR, e.message ?: getString(R.string.error_prepare)))
    }
  }

  private fun shutdown(final: StreamStatus.Snapshot) {
    stream?.let {
      if (it.isStreaming) it.stopStream()
      it.release()
    }
    stream = null
    projection?.stop()
    projection = null
    StreamStatus.post(final)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    stream?.release()
    projection?.stop()
    super.onDestroy()
  }

  // --- ConnectChecker ---

  override fun onConnectionStarted(url: String) {}

  override fun onConnectionSuccess() {
    StreamStatus.post(StreamStatus.Snapshot(StreamStatus.State.LIVE, startedAt = System.currentTimeMillis()))
    updateNotification(getString(R.string.notif_live))
  }

  override fun onConnectionFailed(reason: String) {
    val s = stream ?: return
    if (s.getStreamClient().reTry(RETRY_DELAY_MS, reason)) {
      StreamStatus.post(StreamStatus.Snapshot(StreamStatus.State.RECONNECTING, reason))
      updateNotification(getString(R.string.notif_reconnecting))
    } else {
      handler.post { shutdown(StreamStatus.Snapshot(StreamStatus.State.ERROR, reason)) }
    }
  }

  override fun onNewBitrate(bitrate: Long) {
    val cur = StreamStatus.current
    if (cur.state == StreamStatus.State.LIVE) StreamStatus.post(cur.copy(bitrate = bitrate))
  }

  override fun onDisconnect() {}

  override fun onAuthError() {
    handler.post { shutdown(StreamStatus.Snapshot(StreamStatus.State.ERROR, getString(R.string.error_auth))) }
  }

  override fun onAuthSuccess() {}

  // --- Notificación ---

  private fun fgsTypes(): Int {
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    return types
  }

  private fun buildNotification(text: String): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
      )
    }
    val stop = PendingIntent.getService(
      this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val open = PendingIntent.getActivity(
      this, 2, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(open)
      .addAction(0, getString(R.string.action_end), stop)
      .build()
  }

  private fun updateNotification(text: String) {
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
  }

  companion object {
    const val ACTION_START = "cl.digitalmonster.opencast.START"
    const val ACTION_STOP = "cl.digitalmonster.opencast.STOP"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_DATA = "data"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_QUALITY = "quality"
    const val EXTRA_AUDIO = "audio"

    private const val CHANNEL_ID = "live"
    private const val NOTIFICATION_ID = 7
    private const val FPS = 30
    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_BITRATE = 128_000
    private const val RETRIES = 10
    private const val RETRY_DELAY_MS = 5000L

    fun stop(context: Context) {
      context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
    }
  }
}
