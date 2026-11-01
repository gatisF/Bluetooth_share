package lv.gatis.btshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "lv.gatis.btshare.PLAY"
        const val ACTION_TOGGLE = "lv.gatis.btshare.TOGGLE"
        const val ACTION_NEXT = "lv.gatis.btshare.NEXT"
        const val ACTION_STOP = "lv.gatis.btshare.STOP"
        const val EXTRA_URIS = "uris"

        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var status: String = "Nothing playing."
        @Volatile var listener: (() -> Unit)? = null
    }

    private var player: MediaPlayer? = null
    private var queue: List<Uri> = emptyList()
    private var index = 0
    private var prepared = false
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        audioManager = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "BluetoothShare").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onStop() = stopAll()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                @Suppress("DEPRECATION")
                val uris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(EXTRA_URIS)
                showNotification()
                if (!uris.isNullOrEmpty()) {
                    queue = uris
                    index = 0
                    playCurrent()
                }
            }
            ACTION_TOGGLE -> { showNotification(); toggle() }
            ACTION_NEXT -> { showNotification(); next() }
            ACTION_STOP -> stopAll()
            else -> showNotification()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player?.release()
        player = null
        session.release()
        abandonFocus()
        running = false
        status = "Nothing playing."
        listener?.invoke()
        super.onDestroy()
    }

    private fun playCurrent() {
        player?.release()
        prepared = false
        val uri = queue.getOrNull(index) ?: return
        status = "Loading ${titleOf(uri)}…"
        publish()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                prepared = true
                requestFocus()
                it.start()
                publish()
            }
            mp.setOnCompletionListener { next() }
            mp.setOnErrorListener { _, _, _ ->
                status = "Can't play ${titleOf(uri)}"
                publish()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            status = "Can't play ${titleOf(uri)}: ${e.message}"
            publish()
        }
    }

    private fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Throwable) { false }

    private fun toggle() = if (isPlaying()) pause() else resume()

    private fun pause() {
        if (prepared) player?.pause()
        publish()
    }

    private fun resume() {
        if (prepared) {
            requestFocus()
            player?.start()
        } else if (player == null && queue.isNotEmpty()) {
            playCurrent()
        }
        publish()
    }

    private fun next() {
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        playCurrent()
    }

    private fun stopAll() {
        player?.release()
        player = null
        prepared = false
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestFocus() {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build().also { focusRequest = it }
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun titleOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment ?: "track"
    }

    private fun publish() {
        val uri = queue.getOrNull(index)
        val title = uri?.let { titleOf(it) } ?: "Nothing"
        val playing = isPlaying()
        if (prepared) {
            val route = try { player?.routedDevice?.productName?.toString() } catch (_: Throwable) { null }
            status = buildString {
                append(if (playing) "Playing" else "Paused")
                append(": $title (${index + 1}/${queue.size})")
                if (!route.isNullOrBlank()) append("\nOutput: $route")
            }
        }
        session.setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    try { player?.currentPosition?.toLong() ?: 0L } catch (_: Throwable) { 0L },
                    1f
                )
                .build()
        )
        showNotification()
        listener?.invoke()
    }

    private fun action(icon: Int, label: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(),
            Intent(this, PlayerService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun showNotification() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = isPlaying()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(queue.getOrNull(index)?.let { titleOf(it) } ?: getString(R.string.app_name))
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
