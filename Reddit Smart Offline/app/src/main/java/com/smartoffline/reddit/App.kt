package com.smartoffline.reddit

import android.app.Application
import com.smartoffline.reddit.data.AppDatabase
import com.smartoffline.reddit.data.Prefs
import com.smartoffline.reddit.data.Repository
import com.smartoffline.reddit.data.SecureStore
import com.smartoffline.reddit.net.RedditClient
import com.smartoffline.reddit.storage.MediaStorage
import com.smartoffline.reddit.sync.SyncEngine
import com.smartoffline.reddit.sync.SyncScheduler
import com.smartoffline.reddit.util.Notifier

/** Application: crash reporting, shake-to-feedback and the app's single instances (manual DI). */
class App : Application() {
    lateinit var repo: Repository; private set
    lateinit var prefs: Prefs; private set
    lateinit var secure: SecureStore; private set
    lateinit var reddit: RedditClient; private set
    lateinit var storage: MediaStorage; private set
    lateinit var notifier: Notifier; private set
    lateinit var engine: SyncEngine; private set
    lateinit var scheduler: SyncScheduler; private set

    companion object {
        private lateinit var instance: App
        fun get(): App = instance
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        installShakeFeedback()
        instance = this
        repo = Repository(AppDatabase(this))
        prefs = Prefs(repo)
        secure = SecureStore(prefs)
        reddit = RedditClient(prefs, secure)
        storage = MediaStorage(this, prefs)
        notifier = Notifier(this).apply { createChannels() }
        engine = SyncEngine(repo, prefs, reddit, storage, notifier)
        scheduler = SyncScheduler(this, repo)
        // A process that died mid-sync leaves runs marked RUNNING: close them, downloads resume next time.
        repo.closeInterruptedHistory()
        scheduler.scheduleAll(prefs.value, replace = false)
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Proves to AppMama which app sent the report.
                val sender = android.app.PendingIntent.getBroadcast(
                    this, 0,
                    android.content.Intent("dev.appmama.action.REPORT_CRASH").setPackage(packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                startActivity(
                    android.content.Intent("dev.appmama.action.REPORT_CRASH")
                        .setPackage("dev.appmama")
                        .putExtra("dev.appmama.extra.PACKAGE", packageName)
                        .putExtra("dev.appmama.extra.SENDER", sender)
                        .putExtra("dev.appmama.extra.TRACE", error.stackTraceToString().take(20000))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
            // The system's handler logs the crash and ends the process.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun installShakeFeedback() {
        val app: android.app.Application = this
        val appMamaInstalled = try {
            packageManager.getPackageInfo("dev.appmama", 0)
            true
        } catch (_: Exception) {
            false
        }
        if (appMamaInstalled) registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private val sensors = app.getSystemService(android.hardware.SensorManager::class.java)
            private var listener: android.hardware.SensorEventListener? = null

            override fun onActivityResumed(activity: android.app.Activity) {
                val accelerometer = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
                var jolts = 0
                var firstJolt = 0L
                var lastJolt = 0L
                listener = object : android.hardware.SensorEventListener {
                    override fun onSensorChanged(event: android.hardware.SensorEvent) {
                        val (x, y, z) = event.values
                        // About 2.7 g: a deliberate shake, not walking or tilting.
                        if (x * x + y * y + z * z < 26f * 26f) return
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastJolt < 200) return
                        if (now - firstJolt > 1500) { jolts = 0; firstJolt = now }
                        lastJolt = now
                        if (++jolts == 3) sendScreenshot(activity)
                    }
                    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
                }
                sensors.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                listener?.let { sensors?.unregisterListener(it) }
                listener = null
            }

            private fun sendScreenshot(activity: android.app.Activity) {
                val view = activity.window.decorView
                if (view.width == 0 || view.height == 0) return
                val shot = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                // PixelCopy sees what is on screen, SurfaceView games included.
                android.view.PixelCopy.request(activity.window, shot, { result ->
                    if (result != android.view.PixelCopy.SUCCESS) return@request
                    val scale = minOf(1f, 1080f / maxOf(shot.width, shot.height))
                    val small = android.graphics.Bitmap.createScaledBitmap(
                        shot, (shot.width * scale).toInt().coerceAtLeast(1), (shot.height * scale).toInt().coerceAtLeast(1), true,
                    )
                    val jpeg = java.io.ByteArrayOutputStream()
                        .also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) }
                        .toByteArray()
                    try {
                        // Proves to AppMama which app sent the screenshot.
                        val sender = android.app.PendingIntent.getBroadcast(
                            app, 1,
                            android.content.Intent("dev.appmama.action.SEND_FEEDBACK").setPackage(app.packageName),
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                        )
                        activity.startActivity(
                            android.content.Intent("dev.appmama.action.SEND_FEEDBACK")
                                .setPackage("dev.appmama")
                                .putExtra("dev.appmama.extra.PACKAGE", app.packageName)
                                .putExtra("dev.appmama.extra.SENDER", sender)
                                .putExtra("dev.appmama.extra.SCREENSHOT", jpeg)
                        )
                    } catch (_: Throwable) {
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
