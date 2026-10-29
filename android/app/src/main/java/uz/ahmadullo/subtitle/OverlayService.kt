package uz.ahmadullo.subtitle

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YouTube ilovasi ustida subtitle chiqaradigan foreground service.
 *
 * Overlay oynasi butun ekranni egallaydi (LAYOUT_IN_SCREEN + NO_LIMITS,
 * inset'larsiz) va o'lchami hech qachon o'zgarmaydi — matn joyi oyna
 * ichida bottomMargin orqali beriladi. WRAP_CONTENT oyna 0x0 ga tushgach
 * ba'zi firmware'larda (MIUI) qayta kattalashmaydi.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var settings: Settings
    private lateinit var store: SubtitleStore
    private lateinit var watcher: MediaWatcher

    private lateinit var root: FrameLayout
    private lateinit var textView: TextView
    private lateinit var params: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())

    private var record: SubtitleStore.Record? = null
    private var cues: List<Cue> = emptyList()
    private var currentTitle = ""

    private var lastKey: String? = null
    private var lastNotification = ""
    private var placing = false

    private var pausedSince = 0L
    private var flashText = ""
    private var flashUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, if (watcher.isPlaying) TICK_PLAYING else TICK_IDLE)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SubtitleStore.KEY_INDEX) {
            loadRecord()
        } else {
            applyLayout()
        }
        render()
        updateNotification()
    }

    // --------------------------------------------------
    // Lifecycle
    // --------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WindowManager::class.java)
        settings = Settings(this)
        store = SubtitleStore(this)

        createChannel()
        startInForeground(buildNotification())

        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createOverlay()

        watcher = MediaWatcher(this) { onMediaChanged() }
        watcher.start()

        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        running = true
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::watcher.isInitialized) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_MINUS -> shiftOffset(-OFFSET_STEP)
            ACTION_PLUS -> shiftOffset(OFFSET_STEP)
            ACTION_PLACE -> setPlacing(!placing)
            ACTION_TOGGLE -> settings.hidden = !settings.hidden
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return

        // Burilishdan keyin o'lcham darhol yangilanmasligi mumkin
        applyLayout()
        handler.postDelayed({ applyLayout() }, 300)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)

        if (::watcher.isInitialized) {
            watcher.stop()
            settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }

        if (::root.isInitialized && root.isAttachedToWindow) wm.removeView(root)

        super.onDestroy()
    }

    // --------------------------------------------------
    // Overlay
    // --------------------------------------------------

    private fun createOverlay() {
        textView = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            includeFontPadding = false
            visibility = View.GONE
        }

        root = FrameLayout(this).apply {
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // Status/navigation bar'lar paydo bo'lsa ham joy siljimasin
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }

            // Android 12+: shaffofligi 0.8 dan yuqori overlay ostidagi
            // ilovaga teginishni bloklaydi
            alpha = PASSTHROUGH_ALPHA
        }

        setupDrag()
        applyLayout()
        wm.addView(root, params)
    }

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            return Point(b.width(), b.height())
        }
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    private fun statusBarHeight(): Int {
        @SuppressLint("InternalInsetResource", "DiscouragedApi")
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private class Geometry(
        val screenH: Int,
        val landscape: Boolean,
        val videoW: Int,
        val videoH: Int,
        /** Portrait: video maydoni pastidan ekran pastigacha, px */
        val belowVideo: Int
    )

    private fun geometry(): Geometry {
        val size = screenSize()
        val w = size.x
        val h = size.y
        val landscape = w > h

        return if (landscape) {
            // Fullscreen: 16:9 video ekran balandligini to'ldiradi
            Geometry(h, true, min(w, h * 16 / 9), h, 0)
        } else {
            // Portrait: video status bar ostida, ekran kengligida
            val videoH = w * 9 / 16
            Geometry(h, false, w, videoH, max(0, h - statusBarHeight() - videoH))
        }
    }

    private fun applyLayout() {
        if (!::root.isInitialized) return

        val g = geometry()
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        val fontPx = max(minPx, g.videoH * settings.fontSize / 100f)

        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
        textView.maxWidth = (g.videoW * 0.92f).roundToInt()

        val pad = (fontPx * 0.3f).roundToInt()
        textView.setPadding(pad, pad / 3, pad, pad / 3)

        val alpha = (settings.bgOpacity.coerceIn(0, 100) * 255 / 100)
        textView.background = GradientDrawable().apply {
            setColor(Color.argb(alpha, 0, 0, 0))
            cornerRadius = fontPx * 0.15f
        }

        setBottom(
            if (g.landscape) {
                (g.screenH * settings.bottomLandscape / 100f).roundToInt()
            } else {
                g.belowVideo + (g.videoH * settings.bottomPortrait / 100f).roundToInt()
            }
        )
    }

    private val bottom get() = (textView.layoutParams as FrameLayout.LayoutParams).bottomMargin

    private fun setBottom(px: Int) {
        val lp = textView.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin == px) return
        lp.bottomMargin = px
        textView.layoutParams = lp
    }

    // --------------------------------------------------
    // Joylashtirish rejimi (barmoq bilan surish)
    // --------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var downY = 0f
        var startY = 0

        root.setOnTouchListener { _, e ->
            if (!placing) return@setOnTouchListener false

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startY = bottom
                }
                MotionEvent.ACTION_MOVE -> {
                    // Pastdan o'lchanadi: barmoq yuqoriga → margin kattalashadi
                    setBottom(max(0, startY - (e.rawY - downY).roundToInt()))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePosition()
            }
            true
        }
    }

    private fun savePosition() {
        val g = geometry()
        if (g.landscape) {
            settings.bottomLandscape = bottom * 100f / g.screenH
        } else {
            settings.bottomPortrait = (bottom - g.belowVideo) * 100f / g.videoH
        }
    }

    private fun setPlacing(value: Boolean) {
        placing = value

        params.flags = if (value) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.alpha = if (value) 1f else PASSTHROUGH_ALPHA
        wm.updateViewLayout(root, params)

        lastKey = null
        render()
        updateNotification()
    }

    // --------------------------------------------------
    // Media va subtitle
    // --------------------------------------------------

    private fun onMediaChanged() {
        if (watcher.title != currentTitle) {
            currentTitle = watcher.title
            loadRecord()
        }

        if (watcher.state == PlaybackState.STATE_PAUSED) {
            if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
        } else {
            pausedSince = 0L
        }

        render()
        updateNotification()
    }

    private fun loadRecord() {
        val next = store.resolve(currentTitle)

        if (next?.id != record?.id) {
            cues = if (next != null) store.loadCues(next) else emptyList()
        }
        record = next
        lastKey = null
    }

    private fun shiftOffset(step: Double) {
        val r = record ?: run {
            flash(getString(R.string.no_subtitle))
            return
        }

        val offset = Math.round((r.offset + step) * 100) / 100.0
        record = r.copy(offset = offset)
        store.setOffset(r.id, offset)

        flash(getString(R.string.offset_value, SubtitleParser.formatOffset(offset)))
    }

    private fun flash(message: String) {
        flashText = message
        flashUntil = SystemClock.elapsedRealtime() + 1500
        render()
    }

    private fun isVisibleState(): Boolean {
        return when (watcher.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING -> true
            // Uzoq pauza — foydalanuvchi YouTube'dan chiqib ketgan bo'lishi mumkin
            PlaybackState.STATE_PAUSED ->
                pausedSince == 0L || SystemClock.elapsedRealtime() - pausedSince < PAUSE_HIDE_MS
            else -> false
        }
    }

    private fun render() {
        if (!::textView.isInitialized) return

        val now = SystemClock.elapsedRealtime()

        val text: CharSequence = when {
            now < flashUntil -> flashText
            placing -> getString(R.string.placing_hint)
            settings.hidden || record == null || !isVisibleState() -> ""
            else -> {
                val active = SubtitleParser.activeCues(cues, watcher.positionSeconds() - (record?.offset ?: 0.0))
                val key = active.joinToString(" ") { "${it.s}|${it.t}" }
                if (key == lastKey) return
                lastKey = key
                styled(active)
            }
        }

        if (text is String) {
            if (text == lastKey) return
            lastKey = text
        }

        textView.text = text
        textView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun styled(active: List<Cue>): CharSequence {
        val sb = SpannableStringBuilder()

        active.forEachIndexed { i, cue ->
            if (i > 0) sb.append('\n')

            for (seg in SubtitleParser.segments(cue.t)) {
                val start = sb.length
                sb.append(seg.text)
                val end = sb.length

                val style = when {
                    seg.bold && seg.italic -> Typeface.BOLD_ITALIC
                    seg.bold -> Typeface.BOLD
                    seg.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (style != Typeface.NORMAL) {
                    sb.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (seg.underline) {
                    sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        return sb
    }

    // --------------------------------------------------
    // Notification
    // --------------------------------------------------

    private fun createChannel() {
        // IMPORTANCE_LOW bo'lsa MIUI bildirishnomani yig'ilgan "jim" guruhga
        // tashlaydi va tugmalar ko'rinmaydi — DEFAULT, lekin tovushsiz
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.createNotificationChannel(channel)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun action(label: String, action: String, code: Int) =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_subtitle),
            label,
            servicePending(action, code)
        ).build()

    private fun statusText(): Pair<String, String> {
        if (!::watcher.isInitialized) return getString(R.string.app_name) to ""

        if (!watcher.hasAccess) {
            return getString(R.string.status_no_access) to getString(R.string.status_open_app)
        }

        if (currentTitle.isEmpty()) {
            return getString(R.string.status_waiting) to ""
        }

        val r = record
        val sub = if (r == null) {
            getString(R.string.status_no_sub)
        } else {
            r.name + "  ·  " + SubtitleParser.formatOffset(r.offset) +
                if (settings.hidden) "  ·  " + getString(R.string.hidden) else ""
        }

        return currentTitle to sub
    }

    private fun buildNotification(): Notification {
        val (title, text) = statusText()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action("−0.5s", ACTION_MINUS, 1))
            .addAction(action("+0.5s", ACTION_PLUS, 2))
            .addAction(
                action(
                    getString(if (placing) R.string.place_done else R.string.place),
                    ACTION_PLACE, 3
                )
            )
            .build()
    }

    private fun updateNotification() {
        val (title, text) = statusText()
        val key = "$title|$text|$placing"
        if (key == lastNotification) return
        lastNotification = key

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        @Volatile
        var running = false
            private set

        const val ACTION_MINUS = "uz.ahmadullo.subtitle.MINUS"
        const val ACTION_PLUS = "uz.ahmadullo.subtitle.PLUS"
        const val ACTION_PLACE = "uz.ahmadullo.subtitle.PLACE"
        const val ACTION_TOGGLE = "uz.ahmadullo.subtitle.TOGGLE"
        const val ACTION_STOP = "uz.ahmadullo.subtitle.STOP"

        private const val CHANNEL_ID = "overlay_controls"
        private const val OLD_CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        private const val TICK_PLAYING = 50L
        private const val TICK_IDLE = 500L
        private const val PAUSE_HIDE_MS = 30_000L
        private const val OFFSET_STEP = 0.5
        private const val PASSTHROUGH_ALPHA = 0.8f

        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, OverlayService::class.java).setAction(action))
        }
    }
}
