package cl.digitalmonster.opencast

/**
 * Destinos conocidos. Cada plataforma entrega dos datos en su panel de creador:
 * la URL del servidor (RTMP/RTMPS) y la clave de transmisión.
 * Si la plataforma tiene un servidor fijo, lo dejamos precargado.
 */
enum class Platform(
  val id: String,
  val label: String,
  val defaultServer: String,
  val keyHint: String,
) {
  TWITCH(
    id = "twitch",
    label = "Twitch",
    defaultServer = "rtmp://live.twitch.tv/app",
    keyHint = "Panel de creador → Configuración → Transmisión",
  ),
  KICK(
    id = "kick",
    label = "Kick",
    defaultServer = "",
    keyHint = "Panel de creador → Configuración → Stream URL y Stream Key",
  ),
  STARVIOS(
    id = "starvios",
    label = "Starvios",
    defaultServer = "",
    keyHint = "Copia el servidor y la clave que te da Starvios para OBS",
  ),
  CUSTOM(
    id = "custom",
    label = "Otro",
    defaultServer = "",
    keyHint = "Cualquier servicio que acepte RTMP o RTMPS",
  );

  companion object {
    fun fromId(id: String?): Platform = entries.firstOrNull { it.id == id } ?: TWITCH
  }
}

enum class Quality(val id: String, val label: String, val width: Int, val height: Int, val bitrate: Int) {
  LOW("480", "480p", 854, 480, 1_500_000),
  MEDIUM("720", "720p", 1280, 720, 3_000_000),
  HIGH("1080", "1080p", 1920, 1080, 4_500_000);

  companion object {
    fun fromId(id: String?): Quality = entries.firstOrNull { it.id == id } ?: MEDIUM
  }
}

enum class AudioMode(val id: String) {
  MIC("mic"),
  GAME_AND_MIC("mix");

  companion object {
    fun fromId(id: String?): AudioMode = entries.firstOrNull { it.id == id } ?: GAME_AND_MIC
  }
}
