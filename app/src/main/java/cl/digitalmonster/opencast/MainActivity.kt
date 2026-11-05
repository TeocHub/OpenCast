package cl.digitalmonster.opencast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

  private lateinit var settings: Settings

  private lateinit var platformGroup: MaterialButtonToggleGroup
  private lateinit var qualityGroup: MaterialButtonToggleGroup
  private lateinit var audioGroup: MaterialButtonToggleGroup
  private lateinit var keyHint: TextView
  private lateinit var serverLayout: TextInputLayout
  private lateinit var serverInput: TextInputEditText
  private lateinit var keyLayout: TextInputLayout
  private lateinit var keyInput: TextInputEditText
  private lateinit var goLive: MaterialButton
  private lateinit var statusDot: View
  private lateinit var statusText: TextView
  private lateinit var formViews: List<View>

  private var shownPlatform: Platform = Platform.TWITCH
  private val ticker = Handler(Looper.getMainLooper())
  private val tick = object : Runnable {
    override fun run() {
      render(StreamStatus.current)
      ticker.postDelayed(this, 1000)
    }
  }

  private val platformButtons = mapOf(
    R.id.platform_twitch to Platform.TWITCH,
    R.id.platform_kick to Platform.KICK,
    R.id.platform_starvios to Platform.STARVIOS,
    R.id.platform_custom to Platform.CUSTOM,
  )
  private val qualityButtons = mapOf(
    R.id.quality_480 to Quality.LOW,
    R.id.quality_720 to Quality.MEDIUM,
    R.id.quality_1080 to Quality.HIGH,
  )
  private val audioButtons = mapOf(
    R.id.audio_mix to AudioMode.GAME_AND_MIC,
    R.id.audio_mic to AudioMode.MIC,
  )

  private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    if (result[Manifest.permission.RECORD_AUDIO] == true || hasMic()) requestCapture()
    else statusText.text = getString(R.string.error_mic)
  }

  private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode != Activity.RESULT_OK || result.data == null) {
      render(StreamStatus.current)
      return@registerForActivityResult
    }
    val endpoint = settings.endpoint(settings.platform) ?: return@registerForActivityResult
    val intent = Intent(this, StreamService::class.java).apply {
      action = StreamService.ACTION_START
      putExtra(StreamService.EXTRA_RESULT_CODE, result.resultCode)
      putExtra(StreamService.EXTRA_DATA, result.data)
      putExtra(StreamService.EXTRA_ENDPOINT, endpoint)
      putExtra(StreamService.EXTRA_QUALITY, settings.quality.id)
      putExtra(StreamService.EXTRA_AUDIO, settings.audioMode.id)
    }
    ContextCompat.startForegroundService(this, intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    settings = Settings(this)

    platformGroup = findViewById(R.id.platform_group)
    qualityGroup = findViewById(R.id.quality_group)
    audioGroup = findViewById(R.id.audio_group)
    keyHint = findViewById(R.id.key_hint)
    serverLayout = findViewById(R.id.server_layout)
    serverInput = findViewById(R.id.server_input)
    keyLayout = findViewById(R.id.key_layout)
    keyInput = findViewById(R.id.key_input)
    goLive = findViewById(R.id.go_live)
    statusDot = findViewById(R.id.status_dot)
    statusText = findViewById(R.id.status_text)
    formViews = listOf(platformGroup, qualityGroup, audioGroup, serverLayout, keyLayout)

    shownPlatform = settings.platform
    platformGroup.check(platformButtons.entries.first { it.value == shownPlatform }.key)
    qualityGroup.check(qualityButtons.entries.first { it.value == settings.quality }.key)
    audioGroup.check(audioButtons.entries.first { it.value == settings.audioMode }.key)
    showPlatform(shownPlatform)

    platformGroup.addOnButtonCheckedListener { _, id, checked ->
      if (!checked) return@addOnButtonCheckedListener
      saveFields()
      shownPlatform = platformButtons.getValue(id)
      settings.platform = shownPlatform
      showPlatform(shownPlatform)
    }
    qualityGroup.addOnButtonCheckedListener { _, id, checked ->
      if (checked) settings.quality = qualityButtons.getValue(id)
    }
    audioGroup.addOnButtonCheckedListener { _, id, checked ->
      if (checked) settings.audioMode = audioButtons.getValue(id)
    }

    goLive.setOnClickListener { onGoLivePressed() }
  }

  override fun onResume() {
    super.onResume()
    StreamStatus.observe { render(it) }
    ticker.post(tick)
  }

  override fun onPause() {
    saveFields()
    StreamStatus.observe(null)
    ticker.removeCallbacks(tick)
    super.onPause()
  }

  private fun showPlatform(p: Platform) {
    keyHint.text = p.keyHint
    serverInput.setText(settings.server(p))
    keyInput.setText(settings.key(p))
    serverLayout.error = null
    keyLayout.error = null
  }

  private fun saveFields() {
    settings.setServer(shownPlatform, serverInput.text?.toString().orEmpty())
    settings.setKey(shownPlatform, keyInput.text?.toString().orEmpty())
  }

  private fun onGoLivePressed() {
    val state = StreamStatus.current.state
    if (state != StreamStatus.State.IDLE && state != StreamStatus.State.ERROR) {
      StreamService.stop(this)
      return
    }
    saveFields()
    if (!validate()) return

    val needed = buildList {
      if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
      ) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (needed.isEmpty()) requestCapture() else permissions.launch(needed.toTypedArray())
  }

  private fun validate(): Boolean {
    val server = serverInput.text?.toString().orEmpty().trim()
    val key = keyInput.text?.toString().orEmpty().trim()
    serverLayout.error = when {
      server.isEmpty() -> getString(R.string.error_server_empty)
      !server.startsWith("rtmp://") && !server.startsWith("rtmps://") -> getString(R.string.error_server_format)
      else -> null
    }
    keyLayout.error = if (key.isEmpty()) getString(R.string.error_key_empty) else null
    return serverLayout.error == null && keyLayout.error == null
  }

  private fun requestCapture() {
    val manager = getSystemService(MediaProjectionManager::class.java)
    val intent = if (Build.VERSION.SDK_INT >= 34) {
      // Deja elegir entre pantalla completa o una sola app (recomendado: solo el juego).
      manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
    } else {
      manager.createScreenCaptureIntent()
    }
    capture.launch(intent)
  }

  private fun hasMic() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun render(s: StreamStatus.Snapshot) {
    val busy = s.state != StreamStatus.State.IDLE && s.state != StreamStatus.State.ERROR
    formViews.forEach { it.isEnabled = busy.not() }
    for (i in 0 until platformGroup.childCount) platformGroup.getChildAt(i).isEnabled = !busy
    for (i in 0 until qualityGroup.childCount) qualityGroup.getChildAt(i).isEnabled = !busy
    for (i in 0 until audioGroup.childCount) audioGroup.getChildAt(i).isEnabled = !busy

    goLive.text = getString(if (busy) R.string.end_live else R.string.go_live)
    goLive.isActivated = busy

    val (dot, text) = when (s.state) {
      StreamStatus.State.IDLE -> R.color.muted to getString(R.string.status_idle)
      StreamStatus.State.CONNECTING -> R.color.accent to getString(R.string.status_connecting)
      StreamStatus.State.RECONNECTING -> R.color.accent to getString(R.string.status_reconnecting)
      StreamStatus.State.ERROR -> R.color.danger to getString(R.string.status_error, s.message)
      StreamStatus.State.LIVE -> {
        val secs = (System.currentTimeMillis() - s.startedAt) / 1000
        val time = "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
        val kbps = if (s.bitrate > 0) " · ${s.bitrate / 1000} kbps" else ""
        R.color.live to getString(R.string.status_live, time) + kbps
      }
    }
    statusDot.backgroundTintList = ContextCompat.getColorStateList(this, dot)
    statusText.text = text
  }
}
