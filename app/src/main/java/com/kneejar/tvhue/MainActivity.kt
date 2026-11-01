package com.kneejar.tvhue

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kneejar.tvhue.hue.BridgeDiscovery
import com.kneejar.tvhue.hue.EntertainmentArea
import com.kneejar.tvhue.hue.HueApi
import com.kneejar.tvhue.hue.PairResult
import com.kneejar.tvhue.mood.MediaWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var message: TextView
    private lateinit var hint: TextView
    private lateinit var ipInput: EditText
    private lateinit var buttons: LinearLayout

    private var setupJob: Job? = null
    private var toggle: Button? = null
    private var brightnessButton: Button? = null

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            SyncService.start(this, result.resultCode, data)
        } else {
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        titleView = findViewById(R.id.title)
        message = findViewById(R.id.message)
        hint = findViewById(R.id.hint)
        ipInput = findViewById(R.id.ip_input)
        buttons = findViewById(R.id.buttons)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncState.status.collect { renderStatus(it) }
            }
        }

        when {
            !prefs.isPaired -> runSetup()
            prefs.areaId == null -> pickArea()
            else -> showMain()
        }
    }

    override fun onResume() {
        super.onResume()
        if (toggle != null) updateHint()
    }

    // ---- Main screen ----

    private fun showMain() {
        setupJob?.cancel()
        clear(getString(R.string.app_name), "")
        toggle = addButton(getString(R.string.turn_on), big = true) { onTogglePressed() }
        brightnessButton = addButton(brightnessLabel()) {
            prefs.brightness = (prefs.brightness + 1) % 3
            brightnessButton?.text = brightnessLabel()
        }
        addButton(getString(R.string.setup_again)) {
            SyncService.stop(this)
            prefs.clearSetup()
            runSetup()
        }
        toggle?.requestFocus()
        renderStatus(SyncState.status.value)
        updateHint()
    }

    private fun onTogglePressed() {
        if (SyncState.status.value.isActive) {
            SyncService.stop(this)
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun renderStatus(status: SyncStatus) {
        val t = toggle ?: return
        t.text = getString(if (status.isActive) R.string.turn_off else R.string.turn_on)
        val area = prefs.areaName?.let { " · $it" }.orEmpty()
        message.text = when (status) {
            SyncStatus.Off -> getString(R.string.status_off) + area
            SyncStatus.Starting -> getString(R.string.status_starting)
            is SyncStatus.Running -> when (status.mode) {
                Mode.SCREEN -> getString(R.string.status_screen) + area
                Mode.MOOD -> getString(R.string.status_mood, status.detail.ifBlank { "protected video" })
                Mode.RECONNECTING -> getString(R.string.status_reconnecting)
            }
            is SyncStatus.Error -> getString(R.string.status_error, status.message)
        }
    }

    private fun brightnessLabel() = getString(R.string.brightness, prefs.brightnessName)

    private fun updateHint() {
        val missing = !MediaWatcher(this).hasAccess()
        hint.visibility = if (missing) View.VISIBLE else View.GONE
        hint.setText(R.string.hint_no_access)
    }

    // ---- Setup flow ----

    private fun runSetup() {
        toggle = null
        setupJob?.cancel()
        setupJob = lifecycleScope.launch {
            clear(getString(R.string.setup_title), getString(R.string.searching))
            val ip = BridgeDiscovery.find(this@MainActivity)
            if (ip == null) askForIp() else pairWith(ip)
        }
    }

    private fun askForIp(problem: String? = null) {
        clear(getString(R.string.not_found_title), problem ?: getString(R.string.enter_ip))
        ipInput.visibility = View.VISIBLE
        prefs.bridgeIp?.let { ipInput.setText(it) }
        ipInput.requestFocus()
        addButton(getString(R.string.connect), focus = false) {
            val ip = ipInput.text.toString().trim()
            if (IP_PATTERN.matches(ip)) {
                setupJob?.cancel()
                setupJob = lifecycleScope.launch { pairWith(ip) }
            } else {
                Toast.makeText(this, R.string.bad_ip, Toast.LENGTH_SHORT).show()
            }
        }
        addButton(getString(R.string.search_again), focus = false) { runSetup() }
    }

    private suspend fun pairWith(ip: String) {
        clear(getString(R.string.press_button_title), getString(R.string.press_button))
        val api = HueApi(ip)
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.pair() }.getOrElse { PairResult.Failed(getString(R.string.cant_reach, ip)) }
            }
            when (result) {
                is PairResult.Paired -> {
                    prefs.bridgeIp = ip
                    prefs.username = result.username
                    prefs.clientKey = result.clientKey
                    pickArea()
                    return
                }
                is PairResult.PressButton -> delay(2_000)
                is PairResult.Failed -> {
                    askForIp(result.message)
                    return
                }
            }
        }
        clear(getString(R.string.timed_out_title), getString(R.string.timed_out))
        addButton(getString(R.string.try_again)) {
            setupJob = lifecycleScope.launch { pairWith(ip) }
        }
    }

    private fun pickArea() {
        val ip = prefs.bridgeIp
        val key = prefs.username
        if (ip == null || key == null) {
            runSetup()
            return
        }
        setupJob?.cancel()
        setupJob = lifecycleScope.launch {
            clear(getString(R.string.setup_title), getString(R.string.loading_areas))
            val result = withContext(Dispatchers.IO) { runCatching { HueApi(ip).areas(key) } }
            val areas = result.getOrElse {
                clear(getString(R.string.couldnt_load), it.message ?: getString(R.string.cant_reach, ip))
                addButton(getString(R.string.try_again)) { pickArea() }
                addButton(getString(R.string.setup_again)) {
                    prefs.clearSetup()
                    runSetup()
                }
                return@launch
            }
            when (areas.size) {
                0 -> {
                    clear(getString(R.string.no_area_title), getString(R.string.no_area))
                    addButton(getString(R.string.try_again)) { pickArea() }
                }
                1 -> choose(areas[0])
                else -> {
                    clear(getString(R.string.pick_area_title), getString(R.string.pick_area))
                    areas.forEach { area -> addButton(area.name) { choose(area) } }
                }
            }
        }
    }

    private fun choose(area: EntertainmentArea) {
        prefs.areaId = area.id
        prefs.areaName = area.name
        showMain()
    }

    // ---- View helpers ----

    private fun clear(heading: String, body: String) {
        titleView.text = heading
        message.text = body
        hint.visibility = View.GONE
        ipInput.visibility = View.GONE
        buttons.removeAllViews()
    }

    private fun addButton(text: String, big: Boolean = false, focus: Boolean = true, onClick: () -> Unit): Button {
        val layout = if (big) R.layout.button_big else R.layout.button
        val button = LayoutInflater.from(this).inflate(layout, buttons, false) as Button
        button.text = text
        button.setOnClickListener { onClick() }
        buttons.addView(button)
        if (focus && buttons.childCount == 1) button.requestFocus()
        return button
    }

    private companion object {
        val IP_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
