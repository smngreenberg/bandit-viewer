package com.smngreenberg.banditviewer.camera

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BanditApiTest {

    @Test
    fun testParseStatusFull() {
        val json = JSONObject("""
            {
                "battery_level_pct": 85,
                "battery_charging": true,
                "viewfinder_active": false,
                "recording_active": true,
                "viewfinder_streaming_port": 4001,
                "unknown_field": "extra"
            }
        """.trimIndent())
        val status = BanditApi.parseStatus(json)
        assertEquals(85, status.batteryPct)
        assertEquals(true, status.charging)
        assertEquals(false, status.viewfinderActive)
        assertEquals(true, status.recordingActive)
        assertEquals(4001, status.viewfinderStreamingPort)
    }

    @Test
    fun testParseStatusMissingFields() {
        val json = JSONObject("{}")
        val status = BanditApi.parseStatus(json)
        assertNull(status.batteryPct)
        assertNull(status.charging)
        assertNull(status.viewfinderActive)
        assertNull(status.recordingActive)
        assertNull(status.viewfinderStreamingPort)
    }

    @Test
    fun testParseStatusExtraFields() {
        val json = JSONObject("{\"extra\": \"data\", \"battery_level_pct\": 50}")
        val status = BanditApi.parseStatus(json)
        assertEquals(50, status.batteryPct)
        assertNull(status.charging)
    }
}
