package com.MeshLink.android.web

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.MeshLink.android.ai.AriaEngineManager

class WebServerManager(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebServerManager"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Requested URI: $uri")

        // Handle API routes
        if (uri.startsWith("/api/")) {
            return handleApiRequest(session)
        }

        // Handle static files
        return serveStaticContent(uri)
    }

    private fun handleApiRequest(session: IHTTPSession): Response {
        // Basic API for the laptop to interact with the mesh
        val uri = session.uri
        
        when (uri) {
            "/api/status" -> {
                val response = """{"status": "online", "mesh_connected": true}"""
                return newFixedLengthResponse(Response.Status.OK, "application/json", response)
            }
            "/api/peers" -> {
                // Return mock data for now, will connect to PeerManager later
                val response = """{"peers": [{"id": "laptop-123", "name": "Laptop", "rssi": -50}]}"""
                return newFixedLengthResponse(Response.Status.OK, "application/json", response)
            }
            "/api/aria" -> {
                if (session.method == Method.POST) {
                    try {
                        val map = HashMap<String, String>()
                        session.parseBody(map)
                        val postData = map["postData"] ?: ""
                        val json = JSONObject(postData)
                        val mode = json.getString("mode")
                        val payload = json.getString("payload")

                        val (responseText, engineName) = runBlocking {
                            AriaEngineManager.chat("MODE: ${mode.uppercase()}\nPAYLOAD: $payload")
                        }
                        
                        val responseJson = JSONObject().apply {
                            put("response", responseText)
                            put("engine", engineName)
                        }
                        return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in /api/aria", e)
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error": "${e.message}"}""")
                    }
                }
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", """{"error": "use POST"}""")
            }
            else -> {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error": "not_found"}""")
            }
        }
    }

    private fun serveStaticContent(uri: String): Response {
        var path = uri
        if (path == "/") {
            path = "/index.html"
        }

        // Remove leading slash for asset manager
        if (path.startsWith("/")) {
            path = path.substring(1)
        }

        try {
            val assetManager = context.assets
            val inputStream: InputStream = assetManager.open("web/$path")
            
            val mimeType = getCustomMimeType(path)
            return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "File not found: $path", e)
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }
    }

    private fun getCustomMimeType(uri: String): String {
        return when {
            uri.endsWith(".html") -> "text/html"
            uri.endsWith(".js") -> "application/javascript"
            uri.endsWith(".css") -> "text/css"
            uri.endsWith(".png") -> "image/png"
            uri.endsWith(".svg") -> "image/svg+xml"
            else -> MIME_PLAINTEXT
        }
    }
}
