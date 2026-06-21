package com.stepmates.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

class StepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val httpClient = OkHttpClient()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("stepmates", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", null) ?: return Result.failure()

        val availabilityStatus = HealthConnectClient.getSdkStatus(applicationContext)
        if (availabilityStatus != HealthConnectClient.SDK_AVAILABLE) return Result.failure()

        val client = HealthConnectClient.getOrCreate(applicationContext)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) return Result.failure()

        return try {
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

            if (stepsByDate.isEmpty()) return Result.success()

            val metricsArray = JSONArray()
            val stepsMetric = JSONObject()
            stepsMetric.put("name", "steps")
            val dataArray = JSONArray()
            for ((dateStr, count) in stepsByDate) {
                val entry = JSONObject()
                entry.put("date", dateStr)
                entry.put("qty", count)
                dataArray.put(entry)
            }
            stepsMetric.put("data", dataArray)
            metricsArray.put(stepsMetric)

            val payload = JSONObject()
            val dataObj = JSONObject()
            dataObj.put("metrics", metricsArray)
            payload.put("data", dataObj)

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(body).build()
            val httpResponse = httpClient.newCall(request).execute()

            if (httpResponse.isSuccessful) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_sync", now).apply()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
