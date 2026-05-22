package com.geoalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var statusCallback: ((String) -> Unit)? = null

    private var destLat: Double  = 0.0
    private var destLon: Double  = 0.0
    private var radiusM: Float   = 3000f
    private var ringtoneUri: String? = null
    private var vibPattern: Int  = 1
    private var alarmTriggered   = false

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannels()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { processLocation(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destLat      = intent?.getDoubleExtra(EXTRA_LAT,       0.0)    ?: 0.0
        destLon      = intent?.getDoubleExtra(EXTRA_LON,       0.0)    ?: 0.0
        radiusM      = intent?.getFloatExtra(EXTRA_RADIUS,     3000f)  ?: 3000f
        ringtoneUri  = intent?.getStringExtra(EXTRA_RINGTONE)
        vibPattern   = intent?.getIntExtra(EXTRA_VIBRATION,    1)      ?: 1
        alarmTriggered = false

        isRunning = true
        startForeground(NOTIF_ID, buildForegroundNotif())
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
        stopAlarm()
    }

    fun setStatusCallback(cb: (String) -> Unit) { statusCallback = cb }

    private fun formatDistance(metres: Float): String = when {
        metres >= 1000f -> "%.1f km away".format(metres / 1000f)
        else            -> "${metres.toInt()} m away"
    }

    // ── GPS updates ──────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .build()
        try {
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) { stopSelf() }
    }

    private fun processLocation(loc: Location) {
        val result = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, destLat, destLon, result)
        val distM = result[0]
        val distLabel = formatDistance(distM)

        if (distM <= radiusM) {
            pushStatus(getString(R.string.status_inside_radius), distLabel)
            if (!alarmTriggered) { alarmTriggered = true; triggerAlarm() }
        } else {
            pushStatus(getString(R.string.status_outside_radius), distLabel)
            if (alarmTriggered) { alarmTriggered = false; stopAlarm() }
        }
    }

    private fun pushStatus(status: String, distance: String = "") {
        val full = if (distance.isEmpty()) status else "$status\n$distance"
        statusCallback?.invoke(full)
        nm().notify(NOTIF_ID, buildForegroundNotif(if (distance.isEmpty()) status else "$status  |  $distance"))
    }

    // ── Alarm ────────────────────────────────────────────────────────────────

    private fun triggerAlarm() {
        playRingtone()
        if (vibPattern > 0) startVibration()
        sendAlarmNotification()
    }

    private fun playRingtone() {
        val uri = ringtoneUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_ALARM)
                        .build()
                )
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            // Fallback to default alarm if custom ringtone is inaccessible
            runCatching {
                val defUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    setDataSource(applicationContext, defUri)
                    isLooping = true; prepare(); start()
                }
            }
        }
    }

    private fun startVibration() {
        // Index 0 = None, 1..4 = patterns below
        val patterns = arrayOf(
            longArrayOf(0, 400, 300),                                                    // 1 Short
            longArrayOf(0, 900, 400),                                                    // 2 Long
            longArrayOf(0, 400, 200, 400, 200, 400, 500),                               // 3 Triple
            longArrayOf(0, 150,100,150,100,150, 350,                                     // 4 SOS  ...
                           500,100,500,100,500, 350,
                           150,100,150,100,150, 800)
        )
        val waveform = patterns.getOrElse(vibPattern - 1) { patterns[0] }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(waveform, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(waveform, 0)
        }
    }

    private fun stopAlarm() {
        mediaPlayer?.apply { runCatching { if (isPlaying) stop() }; release() }
        mediaPlayer = null
        vibrator?.cancel(); vibrator = null
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun sendAlarmNotification() {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm().notify(ALARM_NOTIF_ID,
            NotificationCompat.Builder(this, ALERT_CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.alarm_title))
                .setContentText(getString(R.string.alarm_message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }

    private fun buildForegroundNotif(status: String = getString(R.string.status_tracking_started)): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FG_CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm().createNotificationChannel(
                NotificationChannel(FG_CH, "Geo Alert Tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
            nm().createNotificationChannel(
                NotificationChannel(ALERT_CH, "Geo Alert Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true); enableLights(true)
                }
            )
        }
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        var isRunning = false

        const val EXTRA_LAT       = "extra_lat"
        const val EXTRA_LON       = "extra_lon"
        const val EXTRA_RADIUS    = "extra_radius"
        const val EXTRA_RINGTONE  = "extra_ringtone"
        const val EXTRA_VIBRATION = "extra_vibration"

        private const val FG_CH        = "geo_alert_fg"
        private const val ALERT_CH     = "geo_alert_alarm"
        private const val NOTIF_ID     = 1001
        private const val ALARM_NOTIF_ID = 1002
        private const val INTERVAL_MS  = 5000L
    }
}
