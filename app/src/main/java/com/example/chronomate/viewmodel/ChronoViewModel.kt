package com.example.chronomate.viewmodel

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chronomate.R
import com.example.chronomate.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class ChronoViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(ChronoData())
    val uiState = _uiState.asStateFlow()

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // This client will be swapped when the specific network is available
    @Volatile
    private var activeClient: OkHttpClient = baseClient

    private var lastSeenRawShots = emptyList<Float>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    // --- Connection tracking -------------------------------------------------
    // The chrono is considered connected when - and only when - it answers us.
    // The Wi-Fi callback only tells us how far the *attempt* got; it is never
    // allowed to claim "connected" on its own, because the phone can stay joined
    // to the chrono AP long after the callback has given up on it (and the other
    // way round, the user may have joined the AP manually via Android settings).

    /** Address the chrono answered on, or null while we still have to look for it. */
    @Volatile
    private var chronoUrl: String? = null

    /** True while our own NetworkCallback owns the link to the chrono AP. */
    @Volatile
    private var boundToChronoAp = false

    /** Consecutive failed polls; we tolerate a few before dropping the status. */
    private var missedPolls = 0

    init {
        startPolling()
        loadPreferences(context)
    }

    private fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("chrono_prefs", Context.MODE_PRIVATE)
        
        val calibrationEntriesJson = prefs.getString("calibration_entries", "[]") ?: "[]"
        val calibrationEntries = if (calibrationEntriesJson != "[]" && calibrationEntriesJson.isNotEmpty()) {
            calibrationEntriesJson.split(";").mapNotNull {
                val parts = it.split(":")
                if (parts.size >= 1) {
                    val chronoValue = parts[0].toFloatOrNull() ?: return@mapNotNull null
                    val controlValue = if (parts.size >= 2) parts[1].toFloatOrNull() else null
                    CalibrationEntry(chronoValue, controlValue)
                } else null
            }
        } else emptyList()

        val compMode = CompensationMode.valueOf(prefs.getString("comp_mode", CompensationMode.NONE.name) ?: CompensationMode.NONE.name)
        val chronoType = ChronoType.valueOf(prefs.getString("chrono_type", ChronoType.HT_X3000.name) ?: ChronoType.HT_X3000.name)
        val savedSessions = parseSessions(prefs.getString("saved_sessions", "") ?: "")
        val currentShots = parseShots(prefs.getString("current_shots", "") ?: "")
        val velocities = currentShots.map { it.velocity }
        val avg = if (velocities.isNotEmpty()) velocities.average().toFloat() else 0f
        val max = if (velocities.isNotEmpty()) velocities.maxOrNull() ?: 0f else 0f
        val min = if (velocities.isNotEmpty()) velocities.minOrNull() ?: 0f else 0f
        val es = if (velocities.isNotEmpty()) max - min else 0f
        val varVal = if (velocities.size > 1) {
            val mean = velocities.average()
            velocities.sumOf { (it - mean).pow(2.0) }.toFloat() / velocities.size
        } else 0f

        _uiState.update { it.copy(
            isDarkMode = prefs.getBoolean("dark_mode", true),
            keepScreenOn = prefs.getBoolean("keep_screen_on", false),
            language = prefs.getString("language", "en") ?: "en",
            compensationMode = compMode,
            calibrationEntries = calibrationEntries,
            chronoType = chronoType,
            savedSessions = savedSessions,
            shots = currentShots,
            velocity = currentShots.lastOrNull()?.velocity?.let { v -> "%.2f".format(v) } ?: "0.00",
            currentEnergy = currentShots.lastOrNull()?.energyJoules ?: 0f,
            selectedWeight = currentShots.lastOrNull()?.weightGrams ?: it.selectedWeight,
            averageVelocity = avg,
            maxVelocity = max,
            minVelocity = min,
            extremeSpread = es,
            standardDeviation = sqrt(varVal),
            variance = varVal
        ) }
        
        updateCompensationFactor()

        if (_uiState.value.keepScreenOn) {
            applyKeepScreenOn(context, true)
        }
    }

    private fun savePreference(context: Context, key: String, value: Any) {
        val prefs = context.getSharedPreferences("chrono_prefs", Context.MODE_PRIVATE)
        with(prefs.edit()) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
            }
            apply()
        }
    }

    private fun saveCalibration(context: Context) {
        val state = _uiState.value
        val serialized = state.calibrationEntries.joinToString(";") { 
            "${it.chronoValue}:${it.controlValue ?: ""}"
        }
        savePreference(context, "calibration_entries", serialized)
        savePreference(context, "comp_mode", state.compensationMode.name)
    }

    private fun serializeShot(shot: Shot) =
        "${shot.rawVelocity},${shot.weightGrams},${shot.compensationFactor}"

    private fun parseShot(raw: String): Shot? {
        val parts = raw.split(",")
        if (parts.size < 2) return null
        val velocity = parts[0].toFloatOrNull() ?: return null
        val weight = parts[1].toFloatOrNull() ?: return null
        val factor = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        return Shot(velocity, weight, factor)
    }

    private fun parseShots(raw: String): List<Shot> =
        if (raw.isBlank()) emptyList() else raw.split(";").mapNotNull { parseShot(it) }

    private fun serializeSessions(sessions: List<ShotSession>) =
        sessions.joinToString("\n") { session ->
            listOf(
                session.id,
                session.createdAtMillis.toString(),
                session.shots.joinToString(";") { serializeShot(it) }
            ).joinToString("|")
        }

    private fun parseSessions(raw: String): List<ShotSession> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val createdAt = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (parts[0].isBlank()) return@mapNotNull null
            ShotSession(parts[0], createdAt, parseShots(parts[2]))
        }
    }

    private fun persistSessions(sessions: List<ShotSession>) {
        savePreference(appContext, "saved_sessions", serializeSessions(sessions))
    }

    private fun persistCurrentShots(shots: List<Shot>) {
        savePreference(appContext, "current_shots", shots.joinToString(";") { serializeShot(it) })
    }

    private fun parkLiveSession(shots: List<Shot>, existing: List<ShotSession>): List<ShotSession> {
        if (shots.isEmpty()) return existing
        val session = ShotSession(
            id = UUID.randomUUID().toString(),
            createdAtMillis = System.currentTimeMillis(),
            shots = shots.toList()
        )
        return (listOf(session) + existing).take(50)
    }

    private fun startFreshLiveSession() {
        val current = _uiState.value
        val sessions = parkLiveSession(current.shots, current.savedSessions)
        lastSeenRawShots = emptyList()
        _uiState.update {
            it.copy(
                savedSessions = sessions,
                shots = emptyList(),
                velocity = "0.00",
                fireRate = "0.0",
                averageVelocity = 0f,
                maxVelocity = 0f,
                minVelocity = 0f,
                extremeSpread = 0f,
                standardDeviation = 0f,
                variance = 0f,
                currentEnergy = 0f
            )
        }
        persistSessions(sessions)
        persistCurrentShots(emptyList())
    }

    fun toggleDarkMode(context: Context, enabled: Boolean) {
        _uiState.update { it.copy(isDarkMode = enabled) }
        savePreference(context, "dark_mode", enabled)
    }

    fun toggleKeepScreenOn(context: Context, enabled: Boolean) {
        _uiState.update { it.copy(keepScreenOn = enabled) }
        savePreference(context, "keep_screen_on", enabled)
        applyKeepScreenOn(context, enabled)
    }

    private fun applyKeepScreenOn(context: Context, enabled: Boolean) {
        if (context is Activity) {
            if (enabled) {
                context.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                context.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    fun setLanguage(context: Context, langCode: String) {
        savePreference(context, "language", langCode)
        _uiState.update { it.copy(language = langCode) }
        
        if (context is Activity) {
            val intent = context.intent
            context.finish()
            context.startActivity(intent)
        } else {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun setChronoType(context: Context, type: ChronoType) {
        _uiState.update { it.copy(chronoType = type) }
        savePreference(context, "chrono_type", type.name)
    }

    // Calibration Methods
    fun setCompensationMode(context: Context, mode: CompensationMode) {
        _uiState.update { it.copy(compensationMode = mode) }
        updateCompensationFactor()
        saveCalibration(context)
    }

    fun startCalibration() {
        _uiState.update { it.copy(isCalibrationActive = true) }
    }

    fun stopCalibration() {
        _uiState.update { it.copy(isCalibrationActive = false) }
    }

    fun updateCalibrationControlValue(context: Context, index: Int, value: Float?) {
        _uiState.update { current ->
            val newList = current.calibrationEntries.toMutableList()
            if (index in newList.indices) {
                newList[index] = newList[index].copy(controlValue = value)
            }
            current.copy(calibrationEntries = newList)
        }
        updateCompensationFactor()
        saveCalibration(context)
    }

    fun removeCalibrationEntry(context: Context, index: Int) {
        _uiState.update { current ->
            val newList = current.calibrationEntries.toMutableList()
            if (index in newList.indices) {
                newList.removeAt(index)
            }
            current.copy(calibrationEntries = newList)
        }
        updateCompensationFactor()
        saveCalibration(context)
    }

    fun clearCalibration(context: Context) {
        _uiState.update { it.copy(calibrationEntries = emptyList()) }
        updateCompensationFactor()
        saveCalibration(context)
    }

    private fun updateCompensationFactor() {
        _uiState.update { current ->
            val entriesWithControl = current.calibrationEntries.filter { it.controlValue != null && it.chronoValue > 0 }
            val factor = when (current.compensationMode) {
                CompensationMode.NONE -> 1.0f
                CompensationMode.MEDIAN -> {
                    if (entriesWithControl.isEmpty()) 1.0f
                    else {
                        val ratios = entriesWithControl.map { it.controlValue!! / it.chronoValue }
                        val medianRatio = ratios.sorted()[ratios.size / 2]
                        (1.0f + medianRatio) / 2.0f
                    }
                }
                CompensationMode.TRUST_EXTERNAL -> {
                    if (entriesWithControl.isEmpty()) 1.0f
                    else {
                        val ratios = entriesWithControl.map { it.controlValue!! / it.chronoValue }
                        ratios.sorted()[ratios.size / 2]
                    }
                }
            }
            
            // Re-apply factor to all existing shots to ensure history/stats update instantly
            val updatedShots = current.shots.map { it.copy(compensationFactor = factor) }
            
            // Recalculate stats based on updated shots
            val velocities = updatedShots.map { it.velocity }
            val avg = if (velocities.isNotEmpty()) velocities.average().toFloat() else 0f
            val max = if (velocities.isNotEmpty()) velocities.maxOrNull() ?: 0f else 0f
            val min = if (velocities.isNotEmpty()) velocities.minOrNull() ?: 0f else 0f
            val es = if (velocities.isNotEmpty()) max - min else 0f
            
            val varVal = if (velocities.size > 1) {
                val mean = velocities.average()
                velocities.sumOf { (it - mean).pow(2.0) }.toFloat() / velocities.size
            } else 0f
            val sd = sqrt(varVal)

            val latestShot = updatedShots.lastOrNull()
            val currentVelocityString = latestShot?.velocity?.let { "%.2f".format(it) } ?: "0.00"
            val currentEnergy = latestShot?.energyJoules ?: 0f

            current.copy(
                compensationFactor = factor,
                shots = updatedShots,
                averageVelocity = avg,
                maxVelocity = max,
                minVelocity = min,
                extremeSpread = es,
                standardDeviation = sd,
                variance = varVal,
                velocity = currentVelocityString,
                currentEnergy = currentEnergy
            )
        }
    }

    fun connectToChronoWifi(context: Context) {
        try {
            // Application context: the request outlives the Activity that started it.
            val connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            this.connectivityManager = connectivityManager

            networkCallback?.let { 
                try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) {}
            }

            val requestBuilder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val chrono = _uiState.value.chronoType
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(chrono.ssid)
                    .setWpa2Passphrase("88888888")
                    .build()
                requestBuilder.setNetworkSpecifier(specifier)
            }

            val request = requestBuilder.build()

            // A deliberate reconnect starts a fresh live session: park the previous
            // shots in History and ignore the device's already stored list.
            boundToChronoAp = false
            chronoUrl = null
            missedPolls = 0
            startFreshLiveSession()
            setConnectionState(ConnectionState.CONNECTING)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 1. Bind the process (hint for the OS)
                    connectivityManager.bindProcessToNetwork(network)

                    // 2. Bind the OkHttpClient specifically (guarantee for the app)
                    // We use a custom connection pool to avoid reusing sockets from previous networks
                    activeClient = baseClient.newBuilder()
                        .socketFactory(network.socketFactory)
                        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                        .build()

                    boundToChronoAp = true
                    chronoUrl = null
                    missedPolls = 0

                    // The Wi-Fi is up - but that is not the same as "the chrono is
                    // talking to us". The poller promotes us to CONNECTED as soon as
                    // the device actually answers.
                    setConnectionState(ConnectionState.LINKED)
                }

                override fun onLost(network: Network) {
                    connectivityManager.bindProcessToNetwork(null)
                    activeClient = baseClient
                    boundToChronoAp = false

                    // Do NOT declare a disconnect here: Android drops this callback
                    // routinely (e.g. when the internet-less AP is de-prioritised)
                    // while the phone stays joined and the chrono keeps answering.
                    // Re-run discovery on the default network and let the poll result
                    // decide what the status is.
                    chronoUrl = null
                    missedPolls = 0
                }

                override fun onUnavailable() {
                    boundToChronoAp = false
                    setConnectionState(ConnectionState.FAILED)
                }
            }

            networkCallback = callback
            connectivityManager.requestNetwork(request, callback)
        } catch (e: Exception) {
            setConnectionState(ConnectionState.FAILED, e.message)
        }
    }

    private fun setConnectionState(state: ConnectionState, error: String? = null) {
        _uiState.update { it.copy(connectionState = state, connectionError = error) }
    }

    // --- Polling / status detection -----------------------------------------

    /**
     * Runs forever: finds the chrono, keeps reading it, and keeps the connection
     * status in sync with what is really happening on the wire.
     */
    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val target = chronoUrl
                if (target == null) {
                    discoverChrono()
                } else {
                    val html = fetch(target, activeClient)
                    if (html != null && (boundToChronoAp || looksLikeChronoPage(html))) {
                        onChronoAnswered(html)
                    } else {
                        onPollFailed()
                    }
                }

                delay(if (_uiState.value.isConnected) POLL_INTERVAL_MS else RETRY_INTERVAL_MS)
            }
        }
    }

    /**
     * Looks for the chrono on the addresses the different models are known to use.
     * A page is only accepted if it actually looks like a chronograph page, so a
     * home router on 192.168.1.1 can never be mistaken for the device.
     */
    private fun discoverChrono() {
        val probeClient = activeClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        // Anything that replies at all, used as a fallback when we are sitting on
        // the chrono's own AP and none of the pages matched the known markers.
        var fallback: Pair<String, String>? = null

        for (host in CANDIDATE_HOSTS) {
            val candidate = "http://$host"
            val html = fetch(candidate, probeClient) ?: continue

            if (looksLikeChronoPage(html)) {
                chronoUrl = candidate
                onChronoAnswered(html)
                return
            }
            if (fallback == null) fallback = candidate to html
        }

        val (fallbackUrl, fallbackHtml) = fallback ?: run { onPollFailed(); return }

        if (boundToChronoAp) {
            // We are on the chrono's private AP - whatever answers there is it.
            chronoUrl = fallbackUrl
            onChronoAnswered(fallbackHtml)
        } else {
            onPollFailed()
        }
    }

    private fun fetch(url: String, client: OkHttpClient): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The chrono replied - this is the only place that reports CONNECTED. */
    private fun onChronoAnswered(html: String) {
        missedPolls = 0
        parseHtml(html)
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
            setConnectionState(ConnectionState.CONNECTED)
        }
    }

    /**
     * A poll went nowhere. Single hiccups are ignored - only after
     * [MAX_MISSED_POLLS] in a row do we downgrade the status, and then only to
     * what we can still prove (Wi-Fi link up vs. nothing at all).
     */
    private fun onPollFailed() {
        missedPolls++
        if (missedPolls < MAX_MISSED_POLLS) return

        // Address may have changed (other model, DHCP) - look for it again.
        chronoUrl = null

        val next = when {
            boundToChronoAp -> ConnectionState.LINKED
            // An association attempt is still running / already failed: those states
            // are owned by the network callback, don't overwrite them.
            _uiState.value.connectionState == ConnectionState.CONNECTING -> return
            _uiState.value.connectionState == ConnectionState.FAILED -> return
            else -> ConnectionState.DISCONNECTED
        }
        setConnectionState(next)
    }

    /**
     * Cheap sanity check that a fetched page belongs to a chronograph and not to
     * whatever else happens to serve HTTP on that address.
     */
    private fun looksLikeChronoPage(html: String): Boolean {
        val text = Jsoup.parse(html).text()
        return FIRE_RATE_REGEX.containsMatchIn(text) || CHRONO_MARKER_REGEX.containsMatchIn(text)
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { callback ->
            try { connectivityManager?.unregisterNetworkCallback(callback) } catch (e: Exception) {}
        }
        connectivityManager?.bindProcessToNetwork(null)
        networkCallback = null
        connectivityManager = null
    }

    private fun parseWeightGrams(doc: org.jsoup.nodes.Document, text: String): Float? {
        val fromInput = doc.select("input").firstNotNullOfOrNull { el ->
            val key = "${el.attr("name")} ${el.attr("id")} ${el.attr("placeholder")}".lowercase()
            if (key.contains("weight") || key.contains("wgt") || key.contains("mass") || key.contains("重量")) {
                el.attr("value").replace(',', '.').toFloatOrNull()
            } else null
        }
        if (fromInput != null && fromInput in 0.01f..99.99f) return fromInput

        return WEIGHT_REGEX.find(text)?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?.takeIf { it in 0.01f..99.99f }
    }

    private fun parseLiveVelocity(text: String): Float? {
        return VELOCITY_REGEX.find(text)?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?.takeIf { it in MIN_VELOCITY..MAX_VELOCITY }
    }

    private fun parseShotVelocities(text: String): List<Float> {
        return SHOT_REGEX.findAll(text)
            .mapNotNull { it.groupValues[2].replace(',', '.').toFloatOrNull() }
            .filter { it in MIN_VELOCITY..MAX_VELOCITY }
            .toList()
    }

    private fun chronologicalShots(raw: List<Float>, live: Float?): List<Float> {
        if (raw.isEmpty()) return if (live != null) listOf(live) else emptyList()
        if (live == null || raw.size == 1) return raw
        val dFirst = abs(raw.first() - live)
        val dLast = abs(raw.last() - live)
        return if (dFirst + 0.05f < dLast) raw.asReversed() else raw
    }

    private fun parseHtml(html: String) {
        val doc = Jsoup.parse(html)
        val text = doc.text()

        val fireRate = FIRE_RATE_REGEX.find(text)?.groupValues?.get(1) ?: "0.0"
        val parsedWeight = parseWeightGrams(doc, text)
        val liveVelocity = parseLiveVelocity(text)
        val rawShots = chronologicalShots(parseShotVelocities(text), liveVelocity)

        val previousSessions = _uiState.value.savedSessions
        val previousShots = _uiState.value.shots
        var sessionsToPersist: List<ShotSession>? = null
        var shotsToPersist: List<Shot>? = null

        _uiState.update { current ->
            val currentSessionShots = current.shots.toMutableList()
            val currentCalibrationEntries = current.calibrationEntries.toMutableList()
            val currentWeight = parsedWeight ?: current.selectedWeight
            var updatedLastSeen = lastSeenRawShots
            var savedSessions = current.savedSessions

            if (rawShots.isNotEmpty() && rawShots != lastSeenRawShots) {
                var newShotsToAdd = emptyList<Float>()

                if (lastSeenRawShots.isEmpty()) {
                    // New connection: park app shots and the device's already stored
                    // list as History sessions. Live graph starts empty.
                    if (currentSessionShots.isNotEmpty()) {
                        savedSessions = parkLiveSession(currentSessionShots, savedSessions)
                        currentSessionShots.clear()
                    }
                    val deviceShots = rawShots.map { vel ->
                        Shot(
                            rawVelocity = vel,
                            weightGrams = currentWeight,
                            compensationFactor = current.compensationFactor
                        )
                    }
                    savedSessions = parkLiveSession(deviceShots, savedSessions)
                    newShotsToAdd = emptyList()
                } else {
                    for (i in rawShots.size downTo 0) {
                        val head = rawShots.take(i)
                        if (lastSeenRawShots.size >= i && lastSeenRawShots.takeLast(i) == head) {
                            newShotsToAdd = rawShots.drop(i)
                            break
                        } else if (head.isEmpty() && i == 0) {
                            newShotsToAdd = rawShots
                        }
                    }
                }

                newShotsToAdd.forEach { vel ->
                    currentSessionShots.add(Shot(
                        rawVelocity = vel,
                        weightGrams = currentWeight,
                        compensationFactor = current.compensationFactor
                    ))

                    if (current.isCalibrationActive && currentCalibrationEntries.size < 100) {
                        currentCalibrationEntries.add(CalibrationEntry(vel))
                    }
                }
                updatedLastSeen = rawShots
            }

            lastSeenRawShots = updatedLastSeen

            val latestShot = currentSessionShots.lastOrNull()
            val currentVelocityString = latestShot?.velocity?.let { "%.2f".format(it) } ?: "0.00"
            val currentEnergy = latestShot?.energyJoules ?: 0f

            val velocities = currentSessionShots.map { it.velocity }
            val avg = if (velocities.isNotEmpty()) velocities.average().toFloat() else 0f
            val max = if (velocities.isNotEmpty()) velocities.maxOrNull() ?: 0f else 0f
            val min = if (velocities.isNotEmpty()) velocities.minOrNull() ?: 0f else 0f
            val es = if (velocities.isNotEmpty()) max - min else 0f

            val varVal = if (velocities.size > 1) {
                val mean = velocities.average()
                velocities.sumOf { (it - mean).pow(2.0) }.toFloat() / velocities.size
            } else 0f
            val sd = sqrt(varVal)

            sessionsToPersist = savedSessions
            shotsToPersist = currentSessionShots.toList()

            current.copy(
                velocity = currentVelocityString,
                fireRate = fireRate,
                shots = currentSessionShots,
                savedSessions = savedSessions,
                calibrationEntries = currentCalibrationEntries,
                averageVelocity = avg,
                maxVelocity = max,
                minVelocity = min,
                extremeSpread = es,
                standardDeviation = sd,
                variance = varVal,
                currentEnergy = currentEnergy,
                selectedWeight = currentWeight
            )
        }

        val newSessions = sessionsToPersist
        val newShots = shotsToPersist
        if (newSessions != null && newSessions != previousSessions) persistSessions(newSessions)
        if (newShots != null && newShots != previousShots) persistCurrentShots(newShots)
    }

    fun exportToPdf(context: Context, shotCount: Int, gunName: String = "") {
        viewModelScope.launch {
            val data = _uiState.value
            val shotsToExport = data.shots.takeLast(shotCount)
            
            if (shotsToExport.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No shots to export", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.IO) {
                try {
                    val pdfDocument = PdfDocument()
                    var pageNumber = 1
                    var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    var currentPage = pdfDocument.startPage(pageInfo)
                    var currentCanvas = currentPage.canvas
                    
                    val titlePaint = Paint().apply {
                        textSize = 24f
                        isFakeBoldText = true
                        color = Color.BLACK
                    }
                    val headerPaint = Paint().apply {
                        textSize = 14f
                        isFakeBoldText = true
                        color = Color.DKGRAY
                    }
                    val bodyPaint = Paint().apply {
                        textSize = 12f
                        color = Color.BLACK
                    }
                    val labelPaint = Paint().apply {
                        textSize = 10f
                        color = Color.GRAY
                    }
                    val accentPaint = Paint().apply {
                        color = Color.parseColor("#88FF11")
                    }

                    var y = 50f
                    currentCanvas.drawRect(40f, y, 60f, y + 30f, accentPaint)
                    currentCanvas.drawText(context.getString(R.string.pdf_report_title), 70f, y + 24f, titlePaint)
                    
                    if (gunName.isNotBlank()) {
                        y += 55f
                        currentCanvas.drawText(context.getString(R.string.pdf_gun_label, gunName), 70f, y, headerPaint.apply { textSize = 18f })
                        y += 25f
                    } else {
                        y += 65f
                    }

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    currentCanvas.drawText(context.getString(R.string.pdf_date_label, dateFormat.format(Date())), 40f, y, bodyPaint)
                    y += 20f
                    currentCanvas.drawText(context.getString(R.string.pdf_total_shots, shotsToExport.size), 40f, y, bodyPaint)

                    // Stats Section
                    y += 40f
                    currentCanvas.drawRect(40f, y, 555f, y + 2f, accentPaint)
                    y += 25f
                    currentCanvas.drawText(context.getString(R.string.report_settings), 40f, y, headerPaint.apply { textSize = 14f })

                    y += 30f
                    val col1 = 40f
                    val col2 = 230f
                    val col3 = 420f

                    val velocities = shotsToExport.map { it.velocity }
                    val avg = velocities.average().toFloat()
                    val max = velocities.maxOrNull() ?: 0f
                    val min = velocities.minOrNull() ?: 0f
                    val es = max - min
                    val mean = velocities.average()
                    val varVal = if (velocities.size > 1) velocities.sumOf { (it - mean).pow(2.0) }.toFloat() / velocities.size else 0f
                    val sd = sqrt(varVal)

                    currentCanvas.drawText("${context.getString(R.string.stat_average)}: %.1f m/s".format(avg), col1, y, bodyPaint)
                    currentCanvas.drawText("${context.getString(R.string.stat_max)}: %.1f m/s".format(max), col2, y, bodyPaint)
                    currentCanvas.drawText("${context.getString(R.string.stat_min)}: %.1f m/s".format(min), col3, y, bodyPaint)

                    y += 25f
                    currentCanvas.drawText("${context.getString(R.string.stat_es)}: %.1f m/s".format(es), col1, y, bodyPaint)
                    currentCanvas.drawText("${context.getString(R.string.stat_sd)}: %.2f m/s".format(sd), col2, y, bodyPaint)
                    currentCanvas.drawText("${context.getString(R.string.stat_rof)}: ${data.fireRate} r/m", col3, y, bodyPaint)

                    // Graph Section
                    y += 50f
                    currentCanvas.drawText(context.getString(R.string.velocity_trend), 40f, y, headerPaint)
                    y += 20f

                    val graphHeight = 150f
                    val graphWidth = 475f // Adjusted for labels
                    val graphLeft = 80f // Shifted right for Y axis labels
                    val graphTop = y

                    currentCanvas.drawRect(graphLeft, graphTop, graphLeft + graphWidth, graphTop + graphHeight, Paint().apply {
                        color = Color.LTGRAY
                        alpha = 30
                        style = Paint.Style.FILL
                    })

                    val minV = velocities.minOrNull() ?: 0f
                    val maxV = velocities.maxOrNull() ?: 0f
                    val rangeV = (maxV - minV).coerceAtLeast(1f)
                    val displayMin = (minV - rangeV * 0.2f).coerceAtLeast(0f)
                    val displayMax = maxV + rangeV * 0.2f
                    val displayRange = (displayMax - displayMin).coerceAtLeast(0.1f)

                    // Draw Chart Axis Labels
                    currentCanvas.drawText("%.1f".format(displayMax), graphLeft - 5f, graphTop + 10f, labelPaint.apply { textAlign = Paint.Align.RIGHT })
                    currentCanvas.drawText("%.1f".format(displayMin), graphLeft - 5f, graphTop + graphHeight, labelPaint)
                    currentCanvas.drawText("m/s", graphLeft - 5f, graphTop - 5f, labelPaint)
                    currentCanvas.drawText("SHOT #", graphLeft + graphWidth/2, graphTop + graphHeight + 25f, labelPaint.apply { textAlign = Paint.Align.CENTER })

                    // Draw Average Line (Orange)
                    val avgY = graphTop + graphHeight - ((avg - displayMin) / displayRange) * graphHeight
                    val avgLinePaint = Paint().apply {
                        color = Color.parseColor("#E55A16")
                        strokeWidth = 1f
                        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
                        style = Paint.Style.STROKE
                    }
                    currentCanvas.drawLine(graphLeft, avgY, graphLeft + graphWidth, avgY, avgLinePaint)

                    val path = Path()
                    val graphPaint = Paint().apply {
                        color = Color.parseColor("#4CAF50")
                        strokeWidth = 2f
                        style = Paint.Style.STROKE
                    }

                    shotsToExport.forEachIndexed { index, shot ->
                        val x = graphLeft + (index.toFloat() / (shotsToExport.size - 1).coerceAtLeast(1)) * graphWidth
                        val py = graphTop + graphHeight - ((shot.velocity - displayMin) / displayRange) * graphHeight

                        if (index == 0) path.moveTo(x, py) else path.lineTo(x, py)
                        currentCanvas.drawCircle(x, py, 3f, Paint().apply { color = Color.parseColor("#2E7D32") })
                    }
                    currentCanvas.drawPath(path, graphPaint)

                    y += graphHeight + 45f

                    // Table Header
                    currentCanvas.drawRect(40f, y, 555f, y + 20f, Paint().apply { color = Color.LTGRAY; alpha = 50 })
                    currentCanvas.drawText("#", 50f, y + 15f, headerPaint)
                    currentCanvas.drawText("${context.getString(R.string.weight_name_label)} (g)", 100f, y + 15f, headerPaint)
                    currentCanvas.drawText(context.getString(R.string.pdf_velocity_header), 250f, y + 15f, headerPaint)
                    currentCanvas.drawText(context.getString(R.string.pdf_energy_header), 450f, y + 15f, headerPaint)

                    y += 35f
                    // Now showing shots in chronological order (oldest first)
                    for (index in shotsToExport.indices) {
                        // Pagination check
                        if (y > 780f) {
                            pdfDocument.finishPage(currentPage)
                            pageNumber++
                            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                            currentPage = pdfDocument.startPage(pageInfo)
                            currentCanvas = currentPage.canvas
                            y = 50f

                            // Redraw table header on new page
                            currentCanvas.drawRect(40f, y, 555f, y + 20f, Paint().apply { color = Color.LTGRAY; alpha = 50 })
                            currentCanvas.drawText("#", 50f, y + 15f, headerPaint)
                            currentCanvas.drawText("${context.getString(R.string.weight_name_label)} (g)", 100f, y + 15f, headerPaint)
                            currentCanvas.drawText(context.getString(R.string.pdf_velocity_header), 250f, y + 15f, headerPaint)
                            currentCanvas.drawText(context.getString(R.string.pdf_energy_header), 450f, y + 15f, headerPaint)
                            y += 35f
                        }

                        val shot = shotsToExport[index]
                        // shotNum relative to the session
                        val shotNum = (data.shots.size - shotsToExport.size) + index + 1
                        currentCanvas.drawText("%02d".format(shotNum), 50f, y, bodyPaint)
                        currentCanvas.drawText("%.2f".format(shot.weightGrams), 100f, y, bodyPaint)
                        currentCanvas.drawText("%.1f".format(shot.velocity), 250f, y, bodyPaint)
                        currentCanvas.drawText("%.2f".format(shot.energyJoules), 450f, y, bodyPaint)
                        y += 20f
                    }

                    pdfDocument.finishPage(currentPage)

                    // Modern way to save to Downloads using MediaStore
                    val cleanGunName = gunName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
                    val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                    val fileName = if (cleanGunName.isNotBlank()) {
                        "${cleanGunName}_$dateStr.pdf"
                    } else {
                        "ChronoMate_Report_$dateStr.pdf"
                    }
                    
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ChronoMate")
                        }
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            pdfDocument.writeTo(outputStream)
                        }
                        pdfDocument.close()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "PDF saved: $fileName", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        throw Exception("Could not create PDF file in Downloads")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private companion object {
        /** Addresses the supported chrono models are known to serve their page on. */
        val CANDIDATE_HOSTS = listOf("8.8.8.8", "192.168.4.1", "192.168.1.1", "192.168.0.1")

        const val POLL_INTERVAL_MS = 1000L
        const val RETRY_INTERVAL_MS = 3000L
        const val PROBE_TIMEOUT_MS = 1500L

        /** Failed polls tolerated before the status is downgraded. */
        const val MAX_MISSED_POLLS = 3

        val FIRE_RATE_REGEX = "(?i)(?:firerate|fire rate|射速)[^0-9]*(\\d+(?:\\.\\d+)?)".toRegex()
        val WEIGHT_REGEX = "(?i)(?:weight|wgt|mass|pellet|тегло|重量|弹重)[^0-9]*(\\d+(?:[.,]\\d+)?)".toRegex()
        val VELOCITY_REGEX = "(?i)(?:velocity|speed|скорост|速度|初速)[^0-9]*(\\d+(?:[.,]\\d+)?)".toRegex()
        val SHOT_REGEX = "(?<![\\d.])(\\d{1,3})\\s*[:：]\\s*(\\d+(?:[.,]\\d+)?)".toRegex()
        const val MIN_VELOCITY = 0.05f
        const val MAX_VELOCITY = 1500f

        /** Markers that only a chronograph page will carry. */
        val CHRONO_MARKER_REGEX = "(?i)(m/s|fps|ft/s|速度|射速|chrono)".toRegex()
    }
}
