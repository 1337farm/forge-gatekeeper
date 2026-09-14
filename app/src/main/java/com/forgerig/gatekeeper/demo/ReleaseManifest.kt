package com.forgerig.gatekeeper.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseManifest(
    val commit: String = "",
    val built_at: String = "",
    val files: Map<String, String> = emptyMap()
)

suspend fun fetchReleaseManifest(): ReleaseManifest {
    return withContext(Dispatchers.IO) {
        val url = URL("https://github.com/1337farm/forge-gatekeeper/releases/latest/download/aar-manifest.json")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ForgeGatekeeperDemo/1.0")
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Release manifest lookup HTTP ${connection.responseCode}")
        }
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val files = body.optJSONObject("files")
        ReleaseManifest(
            commit = body.optString("commit"),
            built_at = body.optString("built_at"),
            files = if (files == null) emptyMap() else {
                files.keys().asSequence().associateWith { files.getString(it) }
            }
        )
    }
}
