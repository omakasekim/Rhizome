package dev.omakasekim.rhizome

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrapeAndRateLimitTest {
    private val temp = ArrayList<Path>()
    private val closers = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        for (c in closers.reversed()) runCatching { c.close() }
        for (t in temp) t.toFile().deleteRecursively()
    }

    private fun dir(): Path = Files.createTempDirectory("rhizome-v03").also { temp.add(it) }

    private fun runCli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8)).run(arrayOf(*args))
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun scrapeUrlFollowsBep48Convention() {
        assertEquals(
            "http://tracker.example:6969/scrape",
            TrackerClient.scrapeUrl("http://tracker.example:6969/announce").toString(),
        )
        assertEquals(
            "https://tracker.example/sub/scrape.php?passkey=abc",
            TrackerClient.scrapeUrl("https://tracker.example/sub/announce.php?passkey=abc").toString(),
        )
        assertNull(TrackerClient.scrapeUrl("http://tracker.example/tracker/info"))
        assertNull(TrackerClient.scrapeUrl("http://tracker.example/xannounce"))
    }

    @Test
    fun httpScrapeParsesCountsAndEnforcesAddressPolicy() = runBlocking {
        val hashA = ByteArray(20) { (it + 1).toByte() }
        val hashB = ByteArray(20) { (it + 41).toByte() }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        closers.add(AutoCloseable { server.stop(0) })
        server.createContext("/scrape") { ex ->
            val files = mapOf(
                String(hashA, Charsets.ISO_8859_1) to mapOf("complete" to 12, "downloaded" to 345, "incomplete" to 4),
                String(hashB, Charsets.ISO_8859_1) to mapOf("complete" to 0, "downloaded" to 1, "incomplete" to 2),
            )
            val body = Bencode.encode(mapOf("files" to files))
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val announceUrl = "http://127.0.0.1:${server.address.port}/announce"

        // Private loopback must be blocked unless explicitly allowed.
        assertFailsWith<TrackerError> {
            TrackerClient.scrape(announceUrl, listOf(hashA), allowPrivate = false)
        }

        val stats = TrackerClient.scrape(announceUrl, listOf(hashA, hashB), allowPrivate = true)
        assertEquals(TrackerScrape(12, 345, 4), stats[hashA.toHex()])
        assertEquals(TrackerScrape(0, 1, 2), stats[hashB.toHex()])

        val (code, out, err) = runCli("scrape", hashA.toHex(), "--tracker", announceUrl, "--allow-private", "--json")
        assertEquals(0, code, out + err)
        @Suppress("UNCHECKED_CAST")
        val json = JsonReader(out.trim()).read() as Map<String, Any?>
        assertEquals(hashA.toHex(), json["info_hash"])
        @Suppress("UNCHECKED_CAST")
        val rows = json["trackers"] as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        assertEquals(true, rows[0]["ok"])
        assertEquals(12L, rows[0]["complete"])
        assertEquals(345L, rows[0]["downloaded"])
        assertEquals(4L, rows[0]["incomplete"])
    }

    @Test
    fun udpScrapeWorksOverLoopback() = runBlocking {
        val hash = ByteArray(20) { (0xA0 + it).toByte() }
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { closers.add(it) }
        val connectionId = 0x1122334455667788L
        val responder = launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            repeat(2) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val req = ByteBuffer.wrap(buf, 0, packet.length)
                val first = req.getLong()
                val action = req.getInt()
                val tx = req.getInt()
                val reply = when (action) {
                    0 -> ByteBuffer.allocate(16)
                        .putInt(0)
                        .putInt(tx)
                        .putLong(connectionId)
                        .array()
                    2 -> {
                        assertEquals(connectionId, first)
                        ByteBuffer.allocate(20)
                            .putInt(2)
                            .putInt(tx)
                            .putInt(9)
                            .putInt(120)
                            .putInt(3)
                            .array()
                    }
                    else -> error("unexpected udp action $action")
                }
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            }
        }

        val url = "udp://127.0.0.1:${socket.localPort}"
        val stats = withTimeout(10_000) { TrackerClient.scrape(url, listOf(hash), allowPrivate = true) }
        responder.join()
        assertEquals(TrackerScrape(9, 120, 3), stats[hash.toHex()])
    }

    @Test
    fun magnetRoundTripsFromMetainfoAndCli() {
        val raw = Metainfo.create(
            name = "my release 2026",
            files = listOf(listOf("my release 2026") to ByteArray(32_768) { it.toByte() }),
            pieceLength = 16_384,
            trackers = listOf("udp://tracker.example:6969", "https://tracker.example/announce?k=1"),
        )
        val meta = Metainfo.parse(raw)
        val uri = meta.toMagnet()
        assertTrue(uri.startsWith("magnet:?xt=urn:btih:${meta.infoHash.toHex()}"))
        assertTrue("&dn=my%20release%202026" in uri, uri)
        val parsed = Magnet.parse(uri)
        assertContentEquals(meta.infoHash, parsed.infoHash)
        assertEquals(meta.name, parsed.name)
        assertEquals(meta.trackers, parsed.trackers)
        assertEquals(uri, parsed.toUri())

        val torrentFile = dir().resolve("rel.torrent")
        Files.write(torrentFile, raw)
        val (code, out, err) = runCli("magnet", torrentFile.toString(), "--no-trackers", "--json")
        assertEquals(0, code, out + err)
        @Suppress("UNCHECKED_CAST")
        val json = JsonReader(out.trim()).read() as Map<String, Any?>
        val noTrackerMagnet = json["magnet"].toString()
        assertTrue("&tr=" !in noTrackerMagnet, noTrackerMagnet)
        assertContentEquals(meta.infoHash, Magnet.parse(noTrackerMagnet).infoHash)
    }

    @Test
    fun rateLimiterPacesThroughputAndThrottledDownloadCompletes() = runBlocking {
        // 64 KiB/s with 16 KiB initial burst: acquiring 32 KiB consumes burst (16 KiB) + 16 KiB (~250ms).
        val limiter = RateLimiter(bytesPerSecond = 65_536L, burstBytes = 16_384L)
        val start = System.nanoTime()
        limiter.acquire(16_384)
        limiter.acquire(16_384)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        assertTrue(elapsedMs >= 180L, "expected rate limiter to pace second block, elapsed=${elapsedMs}ms")

        // End-to-end download with CLI --max-down / --max-up flags succeeds byte-for-byte.
        val src = dir().resolve("payload.bin")
        val data = ByteArray(48 * 1024).also { Random(42).nextBytes(it) }
        Files.write(src, data)
        val torrent = dir().resolve("payload.torrent")
        assertEquals(0, runCli("create", src.toString(), "--out", torrent.toString(), "--piece-length", "16K").first)
        val meta = Metainfo.parse(Files.readAllBytes(torrent))
        val seeder = RhizomeClient(ClientConfig(allowPrivateAddresses = true, maxUploadBytesPerSec = 512 * 1024)).also {
            closers.add(it)
        }
        val seed = seeder.add(meta, src.parent)
        withTimeout(15_000) { seed.awaitComplete() }

        val target = dir()
        val (code, out, err) = runCli(
            "download", torrent.toString(), "--out", target.toString(),
            "--peer", "127.0.0.1:${seeder.port}", "--no-dht", "--exit-when-done",
            "--max-down", "512K", "--max-up", "256K", "--json",
        )
        assertEquals(0, code, out + err)
        assertContentEquals(data, Files.readAllBytes(target.resolve("payload.bin")))
    }
}
