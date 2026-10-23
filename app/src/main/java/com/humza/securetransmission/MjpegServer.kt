package com.humza.securetransmission

import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Minimal MJPEG-over-HTTP server. Each connected client (the laptop dashboard's
 * <img> tag) gets pushed the latest JPEG frame as it arrives, using the standard
 * multipart/x-mixed-replace stream that browsers render natively — no extra
 * player or native video libs needed, which keeps this buildable via a plain
 * GitHub Actions Gradle build.
 */
class MjpegServer(port: Int) : NanoHTTPD(port) {

    companion object {
        private const val BOUNDARY = "securetransmissionframe"
    }

    private val outputs = CopyOnWriteArrayList<PipedOutputStream>()
    private val executor = Executors.newCachedThreadPool()

    /** Call this with each new JPEG frame as the camera produces it. */
    fun pushFrame(jpegBytes: ByteArray) {
        for (out in outputs) {
            executor.execute {
                try {
                    val header = "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${jpegBytes.size}\r\n\r\n"
                    out.write(header.toByteArray())
                    out.write(jpegBytes)
                    out.write("\r\n".toByteArray())
                } catch (e: Exception) {
                    outputs.remove(out)
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/stream") {
            val pipedIn = PipedInputStream(1024 * 1024)
            val pipedOut = PipedOutputStream(pipedIn)
            outputs.add(pipedOut)

            val response = newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$BOUNDARY",
                pipedIn
            )
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "close")
            return response
        }

        if (session.uri == "/" || session.uri == "/status") {
            val body = """{"service":"Secure Transmission","stream":"/stream"}"""
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    fun shutdownServer() {
        for (out in outputs) {
            try { out.close() } catch (e: Exception) { }
        }
        outputs.clear()
        executor.shutdownNow()
        stop()
    }
}
