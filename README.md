# OpenCast

Transmite en directo desde Android a Twitch, Kick, Starvios o cualquier servicio RTMP, sin PC.

Pensado para jugar y hablar al mismo tiempo: capturas solo la app del juego (Android 14+), así tus notificaciones y el chat no se ven en el directo.

## Descargar

La versión de prueba más reciente está siempre en **Releases → dev → OpenCast.apk**. Se instala encima de la anterior sin perder tu configuración.

## Cómo usarla

1. Copia el **servidor** y la **clave de transmisión** desde el panel de creador de tu plataforma (Twitch ya viene con el servidor puesto).
2. Elige calidad y audio.
3. Toca **Salir en vivo** y, en el aviso del sistema, elige **Una sola app** → tu juego.

## Estado

- [x] Etapa 1: captura de pantalla/app + micrófono y audio del juego → RTMP/RTMPS
- [ ] Etapa 2: varios destinos a la vez
- [ ] Etapa 3: login de Twitch (título, categoría, marcadores) y modo panel con chat en un segundo dispositivo
- [ ] Etapa 4: Kick (API y chat)
- [ ] Etapa 5: escenas y capas (cámara, imágenes, clips)

## Desarrollo

Se compila sola con GitHub Actions en cada cambio a `main`; no hace falta Android Studio. Usa [RootEncoder](https://github.com/pedroSG94/RootEncoder) para la codificación y el envío.

Las builds de prueba se firman con `app/opencast-dev.keystore`, una clave **pública** a propósito para que cualquiera pueda compilar e instalar encima. Las versiones oficiales usarán una clave privada.

## Licencia

Apache 2.0
