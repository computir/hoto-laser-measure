package com.hotomeasure.app

import com.hotomeasure.app.update.Updater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("1.9", "1.8"))
        assertTrue(Updater.isNewer("1.10", "1.9"))
        assertTrue(Updater.isNewer("2.0", "1.99"))
        assertTrue(Updater.isNewer("1.8.1", "1.8"))
        assertFalse(Updater.isNewer("1.8", "1.8"))
        assertFalse(Updater.isNewer("1.7", "1.8"))
        assertFalse(Updater.isNewer("1.8.0", "1.8"))
    }

    // Trimmed from a real GitHub "latest release" response.
    private val sample = """
        {"tag_name":"v1.9","html_url":"https://github.com/computir/hoto-laser-measure/releases/tag/v1.9",
         "body":"Notes here",
         "assets":[
           {"name":"other.zip","browser_download_url":"https://github.com/x/other.zip","size":1},
           {"name":"LaserMeasure.apk","size":2568357,
            "digest":"sha256:ABCDEF0123",
            "browser_download_url":"https://github.com/computir/hoto-laser-measure/releases/download/v1.9/LaserMeasure.apk"}]}
    """.trimIndent()

    @Test
    fun parsesTheApkAssetFromALatestRelease() {
        val r = Updater.parse(Json.parseToJsonElement(sample).jsonObject)!!
        assertEquals("1.9", r.version)
        assertEquals("https://github.com/computir/hoto-laser-measure/releases/download/v1.9/LaserMeasure.apk", r.apkUrl)
        assertEquals(2568357L, r.apkSize)
        assertEquals("abcdef0123", r.sha256)
        assertEquals("Notes here", r.notes)
    }

    @Test
    fun releaseWithoutTheApkIsIgnored() {
        val json = """{"tag_name":"v2.0","assets":[{"name":"source.zip","browser_download_url":"https://x"}]}"""
        assertNull(Updater.parse(Json.parseToJsonElement(json).jsonObject))
    }
}
