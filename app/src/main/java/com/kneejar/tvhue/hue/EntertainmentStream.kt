package com.kneejar.tvhue.hue

import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Low-latency colour stream to the bridge (Hue Entertainment API v2): DTLS 1.2 with a
 * pre-shared key over UDP port 2100. The REST "start" call must be made before [connect].
 */
class EntertainmentStream(
    private val ip: String,
    private val username: String,
    clientKey: String,
    private val areaId: String,
) : Closeable {

    private val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val header = buildHeader()
    private var socket: DatagramSocket? = null
    private var transport: DTLSTransport? = null
    private var sequence = 0

    fun connect() {
        close()
        val s = DatagramSocket()
        try {
            s.connect(InetSocketAddress(ip, 2100))
            val client = object : PSKTlsClient(BcTlsCrypto(SecureRandom()), BasicTlsPSKIdentity(username.toByteArray(), psk)) {
                override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
                override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
                override fun getHandshakeTimeoutMillis(): Int = 10_000
            }
            transport = DTLSClientProtocol().connect(client, UDPTransport(s, 1500))
            socket = s
        } catch (e: Exception) {
            s.close()
            throw if (e is IOException) e else IOException("Couldn't open the light stream: ${e.message}", e)
        }
    }

    /** Sends one frame. Colours are RGB in 0..1, keyed by channel id. */
    fun send(colors: Map<Int, FloatArray>) {
        val t = transport ?: throw IOException("Stream not connected")
        val entries = colors.entries.take(MAX_CHANNELS)
        val buf = ByteArray(header.size + entries.size * 7)
        header.copyInto(buf)
        buf[11] = (sequence++ and 0xff).toByte()
        entries.forEachIndexed { i, (id, rgb) ->
            val o = header.size + i * 7
            buf[o] = id.toByte()
            for (c in 0..2) {
                val v = (rgb[c].coerceIn(0f, 1f) * 65535f).toInt()
                buf[o + 1 + c * 2] = (v shr 8).toByte()
                buf[o + 2 + c * 2] = v.toByte()
            }
        }
        t.send(buf, 0, buf.size)
    }

    override fun close() {
        runCatching { transport?.close() }
        runCatching { socket?.close() }
        transport = null
        socket = null
    }

    private fun buildHeader(): ByteArray {
        val buf = ByteArray(16 + 36)
        "HueStream".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        buf[9] = 0x02 // API version 2.0
        buf[10] = 0x00
        // [11] sequence, [12..13] reserved
        buf[14] = 0x00 // colour space: RGB
        // [15] reserved
        areaId.toByteArray(Charsets.US_ASCII).copyInto(buf, 16, 0, minOf(36, areaId.length))
        return buf
    }

    private companion object {
        const val MAX_CHANNELS = 20
    }
}
