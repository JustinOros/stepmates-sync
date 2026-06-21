package com.stepmates.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.stepmates.sync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private val httpClient = OkHttpClient()

    private val PERMISSIONS = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private val INTERVAL_LABELS = listOf("Every 5 minutes", "Every 15 minutes", "Every 30 minutes (default)", "Every hour", "Every 6 hours", "Manual only")
    private val INTERVAL_MINUTES = listOf(5L, 15L, 30L, 60L, 360L, -1L)
    private val DEFAULT_INTERVAL_INDEX = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("stepmates", Context.MODE_PRIVATE)

        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(PERMISSIONS)) {
                binding.btnGrantPermission.visibility = android.view.View.GONE
                showSuccess("Permission granted! ✓\nTap Sync Now to sync your steps.")
                scheduleBackgroundSync()
            } else {
                binding.btnGrantPermission.visibility = android.view.View.VISIBLE
                showError("Permission denied.\nTap below to try again.")
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, INTERVAL_LABELS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerInterval.adapter = adapter

        val savedIndex = prefs.getInt("interval_index", DEFAULT_INTERVAL_INDEX)
        binding.spinnerInterval.setSelection(savedIndex)

        binding.spinnerInterval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("interval_index", position).apply()
                scheduleBackgroundSync()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val savedUrl = prefs.getString("webhook_url", "") ?: ""
        if (savedUrl.isNotEmpty()) binding.etEmail.setText(savedUrl)

        val lastSync = prefs.getLong("last_sync", 0L)
        if (lastSync > 0) {
            val fmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            binding.tvLastSync.text = "Last synced: ${fmt.format(Date(lastSync))}"
        }

        binding.btnSync.setOnClickListener {
            val url = binding.etEmail.text.toString().trim()
            if (!isValidWebhookUrl(url)) {
                showError("Please paste your StepMates webhook URL.\nIt should start with https://webhook-")
                return@setOnClickListener
            }
            prefs.edit().putString("webhook_url", url).apply()
            hideKeyboard()
            checkHealthConnectAndSync(url)
        }

        binding.btnGrantPermission.setOnClickListener {
            val status = HealthConnectClient.getSdkStatus(this)
            if (status == HealthConnectClient.SDK_UNAVAILABLE || status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
            } else {
                permissionLauncher.launch(PERMISSIONS)
            }
        }

        binding.btnDashboard.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://justinoros.github.io/step-tracker")))
        }

        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { binding.btnSync.performClick(); true } else false
        }

        checkHealthConnectStatus()
    }

    private fun checkHealthConnectStatus() {
        val status = HealthConnectClient.getSdkStatus(this)
        if (status == HealthConnectClient.SDK_UNAVAILABLE || status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            binding.btnGrantPermission.text = "Install Health Connect"
            binding.btnGrantPermission.visibility = android.view.View.VISIBLE
            showError("Health Connect is not installed.\nTap below to install it from the Play Store.")
            return
        }
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(PERMISSIONS)) {
                binding.btnGrantPermission.text = "Grant Health Connect Permission"
                binding.btnGrantPermission.visibility = android.view.View.VISIBLE
                showError("Steps permission not granted.\nTap below to grant access.")
            } else {
                binding.btnGrantPermission.visibility = android.view.View.GONE
                scheduleBackgroundSync()
            }
        }
    }

    private fun scheduleBackgroundSync() {
        val intervalIndex = prefs.getInt("interval_index", DEFAULT_INTERVAL_INDEX)
        val intervalMinutes = INTERVAL_MINUTES[intervalIndex]
        val workManager = WorkManager.getInstance(this)

        if (intervalMinutes == -1L) {
            workManager.cancelUniqueWork("stepmates_sync")
            return
        }

        val request = PeriodicWorkRequestBuilder<StepSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        workManager.enqueueUniquePeriodicWork("stepmates_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun isValidWebhookUrl(url: String) = url.startsWith("https://") && url.contains("token=")

    private fun checkHealthConnectAndSync(webhookUrl: String) {
        val status = HealthConnectClient.getSdkStatus(this)
        if (status == HealthConnectClient.SDK_UNAVAILABLE || status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            binding.btnGrantPermission.text = "Install Health Connect"
            binding.btnGrantPermission.visibility = android.view.View.VISIBLE
            showError("Health Connect is not installed.")
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        setLoading(true)
        showStatus("Checking Health Connect permissions…")
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(PERMISSIONS)) {
                setLoading(false)
                prefs.edit().putString("pending_url", webhookUrl).apply()
                binding.btnGrantPermission.text = "Grant Health Connect Permission"
                binding.btnGrantPermission.visibility = android.view.View.VISIBLE
                showError("Steps permission not granted.\nTap below to grant access.")
                permissionLauncher.launch(PERMISSIONS)
            } else {
                binding.btnGrantPermission.visibility = android.view.View.GONE
                syncSteps(client, webhookUrl)
            }
        }
    }

    private suspend fun syncSteps(client: HealthConnectClient, webhookUrl: String) {
        showStatus("Reading your steps…")
        try {
            val today = LocalDate.now(ZoneId.systemDefault())
            val stepsByDate = mutableMapOf<String, Long>()
            for (i in 0..6) {
                val date = today.minusDays(i.toLong())
                val startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
                val endTime = if (i == 0) Instant.now() else date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
                val response = client.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime)))
                val total = response.records.sumOf { it.count }
                if (total > 0) {
                    val dateKey = "${date.year}-${date.monthValue.toString().padStart(2,'0')}-${date.dayOfMonth.toString().padStart(2,'0')}"
                    stepsByDate[dateKey] = total
                }
            }
            if (stepsByDate.isEmpty()) { setLoading(false); showStatus("No step data found in Health Connect.\nMake sure your fitness app syncs to Health Connect."); return }
            showStatus("Syncing ${stepsByDate.values.sum().toLocaleString()} steps…")
            val metricsArray = JSONArray()
            val stepsMetric = JSONObject()
            stepsMetric.put("name", "steps")
            val dataArray = JSONArray()
            for ((dateStr, count) in stepsByDate) { val entry = JSONObject(); entry.put("date", dateStr); entry.put("qty", count); dataArray.put(entry) }
            stepsMetric.put("data", dataArray)
            metricsArray.put(stepsMetric)
            val payload = JSONObject()
            val dataObj = JSONObject()
            dataObj.put("metrics", metricsArray)
            payload.put("data", dataObj)
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(body).build()
            val response = httpClient.newCall(request).execute()
            setLoading(false)
            if (response.isSuccessful) {
                val todayKey = "${today.year}-${today.monthValue.toString().padStart(2,'0')}-${today.dayOfMonth.toString().padStart(2,'0')}"
                val todaySteps = stepsByDate[todayKey] ?: 0L
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_sync", now).apply()
                val fmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                binding.tvLastSync.text = "Last synced: ${fmt.format(Date(now))}"
                showSuccess("✓ Synced!\n\nToday: ${todaySteps.toLocaleString()} steps")
                scheduleBackgroundSync()
            } else { showError("Sync failed (${response.code}).\nCheck your webhook URL and try again.") }
        } catch (e: Exception) { setLoading(false); showError("Failed to read steps.\n${e.message}") }
    }

    private fun setLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnSync.isEnabled = !loading
            binding.etEmail.isEnabled = !loading
        }
    }

    private fun showStatus(msg: String) { runOnUiThread { binding.tvStatus.text = msg; binding.tvStatus.setTextColor(getColor(android.R.color.white)) } }
    private fun showError(msg: String) { runOnUiThread { binding.tvStatus.text = msg; binding.tvStatus.setTextColor(0xFFFF6B6B.toInt()) } }
    private fun showSuccess(msg: String) { runOnUiThread { binding.tvStatus.text = msg; binding.tvStatus.setTextColor(0xFF4ECA8B.toInt()) } }
    private fun hideKeyboard() { val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(binding.root.windowToken, 0) }
    private fun Long.toLocaleString() = String.format(Locale.getDefault(), "%,d", this)
}
