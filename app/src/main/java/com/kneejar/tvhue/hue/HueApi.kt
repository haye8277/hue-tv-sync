package com.kneejar.tvhue.hue

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class Channel(val id: Int, val x: Float, val y: Float, val z: Float)

data class EntertainmentArea(val id: String, val name: String, val channels: List<Channel>)

sealed class PairResult {
    data class Paired(val username: String, val clientKey: String) : PairResult()
    object PressButton : PairResult()
    data class Failed(val message: String) : PairResult()
}

/** Blocking REST calls to the Hue Bridge. Call from a background dispatcher. */
class HueApi(private val ip: String) {

    fun pair(): PairResult {
        val body = JSONObject()
            .put("devicetype", "tvhue#shield")
            .put("generateclientkey", true)
        val item = JSONArray(request("POST", "/api", body.toString(), null)).getJSONObject(0)
        item.optJSONObject("success")?.let {
            return PairResult.Paired(it.getString("username"), it.getString("clientkey"))
        }
        val error = item.optJSONObject("error")
        return if (error?.optInt("type") == 101) {
            PairResult.PressButton
        } else {
            PairResult.Failed(error?.optString("description") ?: "Unexpected reply from the bridge")
        }
    }

    fun areas(appKey: String): List<EntertainmentArea> {
        val data = JSONObject(request("GET", "/clip/v2/resource/entertainment_configuration", null, appKey))
            .getJSONArray("data")
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            val channels = o.getJSONArray("channels")
            EntertainmentArea(
                id = o.getString("id"),
                name = o.optJSONObject("metadata")?.optString("name") ?: "Entertainment area",
                channels = (0 until channels.length()).map { j ->
                    val c = channels.getJSONObject(j)
                    val p = c.optJSONObject("position") ?: JSONObject()
                    Channel(
                        id = c.getInt("channel_id"),
                        x = p.optDouble("x", 0.0).toFloat(),
                        y = p.optDouble("y", 0.0).toFloat(),
                        z = p.optDouble("z", 0.0).toFloat(),
                    )
                },
            )
        }
    }

    fun setStreaming(appKey: String, areaId: String, on: Boolean) {
        val body = JSONObject().put("action", if (on) "start" else "stop")
        request("PUT", "/clip/v2/resource/entertainment_configuration/$areaId", body.toString(), appKey)
    }

    private fun request(method: String, path: String, body: String?, appKey: String?): String {
        val conn = (URL("https://$ip$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = bridgeSocketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 8_000
            appKey?.let { setRequestProperty("hue-application-key", it) }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Bridge replied $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        // The bridge serves a self-signed certificate on the local network, so normal
        // certificate checks can't pass. This socket factory is only used for bridge calls.
        private val bridgeSocketFactory: SSLSocketFactory by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
        }
    }
}
