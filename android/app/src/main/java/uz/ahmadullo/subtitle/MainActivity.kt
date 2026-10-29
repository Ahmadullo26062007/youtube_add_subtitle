package uz.ahmadullo.subtitle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as SystemSettings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: SubtitleStore

    private val overlayStatus by lazy { findViewById<TextView>(R.id.overlayStatus) }
    private val overlayButton by lazy { findViewById<Button>(R.id.overlayButton) }
    private val listenerStatus by lazy { findViewById<TextView>(R.id.listenerStatus) }
    private val listenerButton by lazy { findViewById<Button>(R.id.listenerButton) }
    private val restrictedHint by lazy { findViewById<TextView>(R.id.restrictedHint) }
    private val appInfoButton by lazy { findViewById<Button>(R.id.appInfoButton) }
    private val notifButton by lazy { findViewById<Button>(R.id.notifButton) }
    private val youtubeTitle by lazy { findViewById<TextView>(R.id.youtubeTitle) }
    private val startButton by lazy { findViewById<Button>(R.id.startButton) }
    private val hideButton by lazy { findViewById<Button>(R.id.hideButton) }
    private val fontLabel by lazy { findViewById<TextView>(R.id.fontLabel) }
    private val fontSeek by lazy { findViewById<SeekBar>(R.id.fontSeek) }
    private val bgLabel by lazy { findViewById<TextView>(R.id.bgLabel) }
    private val bgSeek by lazy { findViewById<SeekBar>(R.id.bgSeek) }
    private val savedList by lazy { findViewById<LinearLayout>(R.id.savedList) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = Settings(this)
        store = SubtitleStore(this)

        overlayButton.setOnClickListener {
            startActivity(
                Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        listenerButton.setOnClickListener {
            startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        appInfoButton.setOnClickListener {
            startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        notifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }

        findViewById<Button>(R.id.loadButton).setOnClickListener { pickFile() }

        startButton.setOnClickListener {
            if (OverlayService.running) {
                stopService(Intent(this, OverlayService::class.java))
                startButton.postDelayed({ refresh() }, 300)
            } else {
                startOverlay()
            }
        }

        hideButton.setOnClickListener {
            settings.hidden = !settings.hidden
            refresh()
        }

        // 2.0% .. 12.0%
        fontSeek.progress = ((settings.fontSize - 2f) * 10).roundToInt()
        fontSeek.setOnSeekBarChangeListener(seekListener { p ->
            settings.fontSize = 2f + p / 10f
            refreshLabels()
        })

        bgSeek.progress = settings.bgOpacity
        bgSeek.setOnSeekBarChangeListener(seekListener { p ->
            settings.bgOpacity = p
            refreshLabels()
        })

        findViewById<Button>(R.id.resetPosButton).setOnClickListener {
            settings.prefs.edit()
                .remove(Settings.KEY_BOTTOM_LAND)
                .remove(Settings.KEY_BOTTOM_PORT)
                .apply()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // --------------------------------------------------
    // Holat
    // --------------------------------------------------

    private fun hasOverlay() = SystemSettings.canDrawOverlays(this)

    private fun hasListener(): Boolean {
        val enabled = SystemSettings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.split(":").any { it.startsWith("$packageName/") }
    }

    private fun hasNotifications() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun currentYoutubeTitle(): String =
        SubtitleStore.normalize(
            MediaWatcher.findYoutube(this)?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        )

    private fun refresh() {
        val overlay = hasOverlay()
        val listener = hasListener()

        overlayStatus.text = permText(overlay, R.string.perm_overlay)
        overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE

        listenerStatus.text = permText(listener, R.string.perm_listener)
        listenerButton.visibility = if (listener) View.GONE else View.VISIBLE
        restrictedHint.visibility = if (listener) View.GONE else View.VISIBLE
        appInfoButton.visibility = if (listener) View.GONE else View.VISIBLE

        notifButton.visibility = if (hasNotifications()) View.GONE else View.VISIBLE

        val title = if (listener) currentYoutubeTitle() else ""
        youtubeTitle.text =
            if (title.isEmpty()) getString(R.string.youtube_none)
            else getString(R.string.youtube_now, title)

        startButton.setText(if (OverlayService.running) R.string.stop else R.string.start)
        hideButton.setText(if (settings.hidden) R.string.show else R.string.hide)

        refreshLabels()
        renderSaved(title)
    }

    private fun permText(ok: Boolean, label: Int) =
        getString(if (ok) R.string.perm_ok else R.string.perm_missing, getString(label))

    private fun refreshLabels() {
        fontLabel.text = getString(R.string.font_size, settings.fontSize)
        bgLabel.text = getString(R.string.bg_opacity, settings.bgOpacity)
    }

    private fun renderSaved(currentTitle: String) {
        savedList.removeAllViews()

        val records = store.list()
        if (records.isEmpty()) {
            savedList.addView(TextView(this).apply { setText(R.string.saved_empty) })
            return
        }

        val pad = (8 * resources.displayMetrics.density).roundToInt()

        for (r in records) {
            val info = TextView(this).apply {
                text = buildString {
                    append(r.name).append('\n')
                    append(
                        if (r.title.isEmpty()) getString(R.string.bound_next)
                        else getString(R.string.bound_to, r.title)
                    ).append('\n')
                    append(getString(R.string.saved_meta, r.count, r.encoding, SubtitleParser.formatOffset(r.offset)))
                }
                setPadding(0, pad, 0, 0)
                if (r.title.isNotEmpty() && r.title == currentTitle) setTypeface(typeface, Typeface.BOLD)
            }

            val bind = Button(this).apply {
                setText(R.string.bind_here)
                isEnabled = currentTitle.isNotEmpty() && r.title != currentTitle
                setOnClickListener {
                    store.bind(r.id, currentTitle)
                    refresh()
                }
            }

            val delete = Button(this).apply {
                setText(R.string.delete)
                setOnClickListener {
                    store.remove(r.id)
                    refresh()
                }
            }

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(bind, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(delete, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }

            savedList.addView(info)
            savedList.addView(buttons)
        }
    }

    // --------------------------------------------------
    // Fayl yuklash
    // --------------------------------------------------

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { importUri(it) }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return

        // Qayta aylantirilganda yana import qilinmasin
        intent?.action = null
        importUri(uri)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) {
            // e'tibor bermaymiz
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle.srt"
    }

    private fun importUri(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = input.readBytes()
                if (buf.size > MAX_FILE_BYTES) {
                    toast(getString(R.string.too_large))
                    return
                }
                buf
            }
        } catch (e: Exception) {
            null
        }

        if (bytes == null) {
            toast(getString(R.string.read_error))
            return
        }

        val (text, encoding) = SubtitleParser.decode(bytes)
        val (format, cues) = SubtitleParser.parse(text)

        if (cues.isEmpty()) {
            toast(getString(R.string.parse_error))
            return
        }

        val parsed = ParsedSubtitle(format, cues, encoding)
        askTitle(displayName(uri), parsed, text)
    }

    private fun askTitle(name: String, parsed: ParsedSubtitle, text: String) {
        val input = EditText(this).apply {
            setText(if (hasListener()) currentYoutubeTitle() else "")
            setSelection(this.text.length)
        }

        val pad = (20 * resources.displayMetrics.density).roundToInt()
        val container = LinearLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bind_title)
            .setMessage(R.string.bind_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                store.add(name, input.text.toString(), parsed, text)
                toast(getString(R.string.loaded, parsed.cues.size))
                refresh()
                if (!OverlayService.running) startOverlay()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startOverlay() {
        if (!hasOverlay() || !hasListener()) {
            toast(getString(R.string.need_permissions))
            return
        }
        OverlayService.start(this)
        startButton.postDelayed({ refresh() }, 300)
    }

    // --------------------------------------------------

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_NOTIF = 2
        private const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}
