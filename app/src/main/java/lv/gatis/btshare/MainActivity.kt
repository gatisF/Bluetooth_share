package lv.gatis.btshare

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var capsText: TextView
    private lateinit var verdictText: TextView
    private lateinit var devicesText: TextView
    private lateinit var outputsText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var permButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val proxies = mutableMapOf<Int, BluetoothProfile>()

    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private val btAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshAll()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshAll()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
            refreshAll()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
            refreshAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        capsText = findViewById(R.id.caps)
        verdictText = findViewById(R.id.verdict)
        devicesText = findViewById(R.id.devices)
        outputsText = findViewById(R.id.outputs)
        nowPlayingText = findViewById(R.id.nowPlaying)
        permButton = findViewById(R.id.btnPerm)

        permButton.setOnClickListener { requestNeededPermissions() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<Button>(R.id.btnOutput).setOnClickListener { openOutputPicker() }
        findViewById<Button>(R.id.btnSharing).setOnClickListener { openAudioSharing() }
        findViewById<Button>(R.id.btnBtSettings).setOnClickListener {
            launchFirst(listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
        }
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickAudio() }
        findViewById<Button>(R.id.btnToggle).setOnClickListener { sendPlayer(PlayerService.ACTION_TOGGLE) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { sendPlayer(PlayerService.ACTION_NEXT) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendPlayer(PlayerService.ACTION_STOP) }

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        PlayerService.listener = { handler.post { updateNowPlaying() } }
        openProfileProxies()
        refreshAll()
    }

    override fun onStop() {
        super.onStop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        PlayerService.listener = null
        val adapter = btAdapter
        // Copy first: closeProfileProxy can call onServiceDisconnected synchronously,
        // which removes from `proxies` while we'd still be iterating it.
        val open = proxies.toList()
        proxies.clear()
        if (adapter != null) {
            for ((profile, proxy) in open) {
                try {
                    adapter.closeProfileProxy(profile, proxy)
                } catch (_: Throwable) {
                }
            }
        }
        proxies.clear()
    }

    // ---- Permissions ----

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        openProfileProxies()
        refreshAll()
    }

    // ---- Capability detection ----

    private fun openProfileProxies() {
        val adapter = btAdapter ?: return
        if (!hasConnectPermission()) return
        val profiles = mutableListOf(BluetoothProfile.A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) profiles += BluetoothProfile.HEARING_AID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) profiles += BluetoothProfile.LE_AUDIO
        for (p in profiles) {
            if (proxies.containsKey(p)) continue
            try {
                adapter.getProfileProxy(this, profileListener, p)
            } catch (_: Throwable) {
            }
        }
    }

    private fun featureLabel(code: Int): String = when (code) {
        BluetoothStatusCodes.FEATURE_SUPPORTED -> "yes"
        BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> "no"
        BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> "unknown (turn Bluetooth on)"
        else -> "unknown ($code)"
    }

    private fun refreshAll() {
        refreshCapabilities()
        refreshDevices()
        refreshOutputs()
        updateNowPlaying()
    }

    private fun refreshCapabilities() {
        permButton.visibility = if (hasConnectPermission()) Button.GONE else Button.VISIBLE

        val sb = StringBuilder()
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")

        val adapter = btAdapter
        var broadcastSupported = false
        var leAudioSupported = false
        if (adapter == null) {
            sb.append("Bluetooth: not available on this device\n")
        } else {
            val enabled = try { adapter.isEnabled } catch (_: Throwable) { false }
            sb.append("Bluetooth: ${if (enabled) "on" else "off"}\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val le = safeFeature { adapter.isLeAudioSupported() }
                val src = safeFeature { adapter.isLeAudioBroadcastSourceSupported() }
                val asst = safeFeature { adapter.isLeAudioBroadcastAssistantSupported() }
                leAudioSupported = le == BluetoothStatusCodes.FEATURE_SUPPORTED
                broadcastSupported = src == BluetoothStatusCodes.FEATURE_SUPPORTED
                sb.append("LE Audio: ${featureLabel(le)}\n")
                sb.append("Auracast broadcast (send to many): ${featureLabel(src)}\n")
                sb.append("Broadcast assistant: ${featureLabel(asst)}")
            } else {
                sb.append("LE Audio / Auracast: needs Android 13 or newer")
            }
        }
        capsText.text = sb.toString()

        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        verdictText.text = when {
            broadcastSupported ->
                "Good news: this phone can broadcast LE Audio (Auracast). Use \"Audio sharing\" to play on two or more LE Audio / Auracast headphones or speakers at once." +
                    if (samsung) " Samsung Dual audio for two classic headsets also works." else ""
            samsung ->
                "Samsung Dual audio: connect two Bluetooth headsets or speakers, tap \"Open output picker\" and tick both devices."
            leAudioSupported ->
                "LE Audio is supported but broadcasting isn't reported. Check \"Audio sharing\" in Bluetooth settings after a system update."
            else ->
                "This phone most likely plays to only one Bluetooth device at a time. Options: speakers with a party mode (JBL PartyBoost/Auracast, Sony Party Connect, UE PartyUp) chained to one another, or a dual-link Bluetooth transmitter."
        }
    }

    private inline fun safeFeature(block: () -> Int): Int =
        try { block() } catch (_: Throwable) { -1 }

    private fun profileName(p: Int): String = when (p) {
        BluetoothProfile.A2DP -> "Classic audio (A2DP)"
        21 -> "Hearing aid"
        22 -> "LE Audio"
        else -> "Profile $p"
    }

    private fun refreshDevices() {
        if (btAdapter == null) {
            devicesText.text = "No Bluetooth adapter."
            return
        }
        if (!hasConnectPermission()) {
            devicesText.text = "Grant the Bluetooth permission to list devices."
            return
        }
        val lines = mutableListOf<String>()
        for ((profile, proxy) in proxies.toList()) {
            val devices = try { proxy.connectedDevices } catch (_: Throwable) { emptyList() }
            for (d in devices) {
                val name = try { d.name } catch (_: Throwable) { null } ?: d.address
                lines += "• $name — ${profileName(profile)}"
            }
        }
        devicesText.text = if (lines.isEmpty()) {
            "Connected: none. Pair and connect your headphones/speakers in Bluetooth settings."
        } else {
            "Connected (${lines.size}):\n" + lines.joinToString("\n")
        }
    }

    private fun outputTypeName(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "classic Bluetooth"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        26 -> "LE Audio headset"   // TYPE_BLE_HEADSET
        27 -> "LE Audio speaker"   // TYPE_BLE_SPEAKER
        30 -> "Auracast broadcast" // TYPE_BLE_BROADCAST
        else -> null
    }

    private fun refreshOutputs() {
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { d -> outputTypeName(d.type)?.let { "• ${d.productName} — $it" } }
            .distinct()
        val broadcasting = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == 30 }
        outputsText.text = buildString {
            append("Bluetooth audio outputs available to apps: ")
            append(if (outs.isEmpty()) "none" else "\n" + outs.joinToString("\n"))
            if (broadcasting) append("\n\nAuracast broadcast is active: audio is being shared.")
        }
    }

    // ---- System pickers ----

    private fun openOutputPicker() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                if (MediaRouter2.getInstance(this).showSystemOutputSwitcher()) return
            } catch (_: Throwable) {
            }
        }
        launchFirst(
            listOf(
                Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", packageName),
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            )
        )
    }

    private fun openAudioSharing() {
        launchFirst(
            listOf(
                Intent("com.android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"),
                Intent("android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"),
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            )
        )
    }

    private fun launchFirst(intents: List<Intent>) {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, "Couldn't open settings on this phone", Toast.LENGTH_SHORT).show()
    }

    // ---- Player ----

    private fun pickAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it }
        } else {
            data.data?.let { uris += it }
        }
        if (uris.isEmpty()) return
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
        val intent = Intent(this, PlayerService::class.java)
            .setAction(PlayerService.ACTION_PLAY)
            .putParcelableArrayListExtra(PlayerService.EXTRA_URIS, uris)
        startForegroundService(intent)
    }

    private fun sendPlayer(action: String) {
        if (!PlayerService.running) {
            if (action != PlayerService.ACTION_STOP) {
                Toast.makeText(this, "Choose audio files first", Toast.LENGTH_SHORT).show()
            }
            return
        }
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    private fun updateNowPlaying() {
        nowPlayingText.text = PlayerService.status
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PICK = 2
    }
}
