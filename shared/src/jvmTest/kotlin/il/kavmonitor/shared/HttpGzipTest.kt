package il.kavmonitor.shared

import com.sun.net.httpserver.HttpServer
import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.createHttpClient
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full-route answer is ~340–375 KB of JSON but ~21–23 KB gzipped (measured 2026-10-06). The app's real HTTP client
 * (OkHttp engine) must ask for gzip and decode it transparently: checked against a local server with a real answer.
 */
class HttpGzipTest {
    @Test fun multiStopRequestIsGzipOnTheWire() = runBlocking {
        val body = fixture("full_route_194/full_193447.json").toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(body) } }.toByteArray()
        var acceptEncoding: String? = null
        var path: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            acceptEncoding = ex.requestHeaders.getFirst("Accept-Encoding")
            path = ex.requestURI.path
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(200, gz.size.toLong())
            ex.responseBody.use { it.write(gz) }
        }
        server.start()
        try {
            val client = createHttpClient()
            val src = CurlbusLiveDataSource(client, baseUrl = "http://127.0.0.1:${server.address.port}")
            val snap = src.multiStopVisits(listOf("20001", "20762", "21705"))
            client.close()
            assertEquals("/20001+20762+21705", path)
            assertTrue(acceptEncoding?.contains("gzip") == true, "Accept-Encoding: $acceptEncoding")
            assertTrue(snap.allVisits.isNotEmpty())
            println("GZIP full-route fixture: ${body.size} B json → ${gz.size} B gzip")
        } finally {
            server.stop(0)
        }
    }
}
