package cl.digitalmonster.opencast

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Preferencias del usuario. Las claves de transmisión se guardan cifradas con una
 * llave del Android Keystore que nunca sale del dispositivo.
 */
class Settings(context: Context) {

  private val prefs = context.getSharedPreferences("opencast", Context.MODE_PRIVATE)

  var platform: Platform
    get() = Platform.fromId(prefs.getString("platform", null))
    set(value) = prefs.edit().putString("platform", value.id).apply()

  var quality: Quality
    get() = Quality.fromId(prefs.getString("quality", null))
    set(value) = prefs.edit().putString("quality", value.id).apply()

  var audioMode: AudioMode
    get() = AudioMode.fromId(prefs.getString("audio", null))
    set(value) = prefs.edit().putString("audio", value.id).apply()

  fun server(platform: Platform): String =
    prefs.getString("server_${platform.id}", null) ?: platform.defaultServer

  fun setServer(platform: Platform, value: String) =
    prefs.edit().putString("server_${platform.id}", value.trim()).apply()

  fun key(platform: Platform): String =
    prefs.getString("key_${platform.id}", null)?.let { decrypt(it) } ?: ""

  fun setKey(platform: Platform, value: String) {
    val clean = value.trim()
    prefs.edit().putString("key_${platform.id}", if (clean.isEmpty()) null else encrypt(clean)).apply()
  }

  /** URL final que recibe el encoder: servidor + "/" + clave. */
  fun endpoint(platform: Platform): String? {
    val server = server(platform).trim().trimEnd('/')
    val key = key(platform)
    if (server.isEmpty() || key.isEmpty()) return null
    if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) return null
    return "$server/$key"
  }

  // --- Cifrado ---

  private fun secretKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()
    )
    return generator.generateKey()
  }

  private fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    return "$iv:$data"
  }

  private fun decrypt(stored: String): String? = try {
    val (iv, data) = stored.split(":", limit = 2)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
    String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
  } catch (_: Exception) {
    null
  }

  private companion object {
    const val KEY_ALIAS = "opencast_stream_keys"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
