package com.smngreenberg.banditviewer.camera

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BanditException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class CameraStatus(
    val batteryPct: Int?,
    val charging: Boolean?,
    val viewfinderActive: Boolean?,
    val recordingActive: Boolean?,
    val viewfinderStreamingPort: Int?
)

class BanditApi(private val baseUrl: String = "http://192.168.1.101/api") {

    fun getVersion(): String {
        val json = request("GET", "/version")
        return json.optString("version", "")
    }

    fun getStatus(): CameraStatus {
        val json = request("GET", "/2/status")
        return parseStatus(json)
    }

    companion object {
        fun parseStatus(json: JSONObject): CameraStatus {
            return CameraStatus(
                batteryPct = if (json.has("battery_level_pct")) json.optInt("battery_level_pct") else null,
                charging = if (json.has("battery_charging")) json.optBoolean("battery_charging") else null,
                viewfinderActive = if (json.has("viewfinder_active")) json.optBoolean("viewfinder_active") else null,
                recordingActive = if (json.has("recording_active")) json.optBoolean("recording_active") else null,
                viewfinderStreamingPort = if (json.has("viewfinder_streaming_port")) json.optInt("viewfinder_streaming_port") else null
            )
        }
    }

    fun startViewfinder(port: Int) {
        val body = JSONObject().apply {
            put("viewfinder_active", true)
            put("viewfinder_streaming_port", port)
        }
        request("POST", "/2/viewfinder", body.toString())
    }

    fun stopViewfinder() {
        val body = JSONObject().apply {
            put("viewfinder_active", false)
            put("viewfinder_streaming_port", -1)
        }
        request("POST", "/2/viewfinder", body.toString())
    }

    private fun request(method: String, path: String, body: String? = null): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(baseUrl + path)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 4000
            connection.readTimeout = 8000
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Content-Type", "application/json")

            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { it.write(body) }
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw BanditException("HTTP error: $responseCode")
            }

            val inputStream = try {
                connection.inputStream
            } catch (e: Exception) {
                null
            } ?: return JSONObject()
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            val response = reader.readText()
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } catch (e: Exception) {
            if (e is BanditException) throw e
            throw BanditException("Request failed: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }
}
