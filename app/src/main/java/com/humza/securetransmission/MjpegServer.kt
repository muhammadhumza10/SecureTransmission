package com.humza.securetransmission

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Minimal MJPEG-over-HTTP server (multipart/x-mixed-replace), which browsers
 * render natively in an <img> tag.
 *
 * Each connected client gets its own [ClientStream] that holds only the LATEST
 * frame. Slow clients simply skip frames; there are no pipes, no shared writer
 * threads and no unbounded backlog, so frames can never interleave or stall.
 */
class MjpegServer(port: Int) : NanoHTTPD(port) {

    companion object {
        private const val BOUNDARY = "securetransmissionframe"
    }

    private val clients = CopyOnWriteArrayList<ClientStream>()

    @Volatile
    private var lastFrame: ByteArray? = null

    /** Call this with each new JPEG frame as the camera produces it. */
    fun pushFrame(jpegBytes: ByteArray) {
        lastFrame = jpegBytes
        for (client in clients) client.offer(jpegBytes)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        if (uri == "/stream") {
            val client = ClientStream()
            // Send the newest frame immediately so the browser sees an image
            // right away instead of waiting for the next camera frame.
            lastFrame?.let { client.offer(it) }
            clients.add(client)

            val response = newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$BOUNDARY",
                client
            )
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Connection", "close")
            return response
        }

        if (uri == "/" || uri == "/status") {
            val body = """{"service":"Secure Transmission","stream":"/stream","clients":${clients.size},"hasFrame":${lastFrame != null}}"""
            val r = newFixedLengthResponse(Response.Status.OK, "application/json", body)
            r.addHeader("Access-Control-Allow-Origin", "*")
            return r
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    fun shutdownServer() {
        for (c in clients) c.close()
        clients.clear()
        stop()
    }

    /**
     * Blocking InputStream that NanoHTTPD reads from to build the chunked
     * response body. read() must block until data is available (returning 0
     * would end the HTTP response) and returns -1 only when closed.
     */
    private inner class ClientStream : InputStream() {
        private val queue = ArrayBlockingQueue<ByteArray>(1)
        private var current: ByteArray? = null
        private var pos = 0

        @Volatile
        private var closed = false

        fun offer(jpeg: ByteArray) {
            if (closed) return
            val header = "--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n\r\n"
            val head = header.toByteArray(Charsets.US_ASCII)
            val tail = "\r\n".toByteArray(Charsets.US_ASCII)
            val part = ByteArray(head.size + jpeg.size + tail.size)
            System.arraycopy(head, 0, part, 0, head.size)
            System.arraycopy(jpeg, 0, part, head.size, jpeg.size)
            System.arraycopy(tail, 0, part, head.size + jpeg.size, tail.size)

            // Keep only the latest frame for this client.
            queue.clear()
            queue.offer(part)
        }

        private fun ensureData(): Boolean {
            while (true) {
                val cur = current
                if (cur != null && pos < cur.size) return true
                if (closed) return false
                val next = try {
                    queue.poll(1, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    return false
                }
                if (next != null) {
                    current = next
                    pos = 0
                }
            }
        }

        override fun read(): Int {
            if (!ensureData()) return -1
            return current!![pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensureData()) return -1
            val cur = current!!
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
            clients.remove(this)
            queue.clear()
        }
    }
}
