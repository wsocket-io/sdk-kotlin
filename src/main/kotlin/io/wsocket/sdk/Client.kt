/**
 * wSocket Kotlin SDK — Realtime Pub/Sub client with Presence, History, and Push.
 *
 * Usage:
 *   val client = createClient("ws://localhost:9001", "your-api-key")
 *   client.connect()
 *   val ch = client.pubsub.channel("chat")
 *   ch.subscribe { data, meta -> println(data) }
 *   ch.publish(mapOf("text" to "hello"))
 */
package io.wsocket.sdk

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ─── Types ──────────────────────────────────────────────────

data class MessageMeta(val id: String, val channel: String, val timestamp: Long)

data class PresenceMember(
    val clientId: String,
    val data: Map<String, Any?>? = null,
    val joinedAt: Long = 0
)

data class HistoryMessage(
    val id: String,
    val channel: String,
    val data: Any?,
    val publisherId: String,
    val timestamp: Long,
    val sequence: Long = 0
)

data class HistoryResult(
    val channel: String,
    val messages: List<HistoryMessage>,
    val hasMore: Boolean = false
)

data class WSocketOptions(
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 10,
    val reconnectDelay: Long = 1000,
    val token: String? = null,
    val recover: Boolean = true
)

// ─── Presence ───────────────────────────────────────────────

class Presence(private val channelName: String, private val sendFn: (Map<String, Any?>) -> Unit) {
    private val enterCallbacks = CopyOnWriteArrayList<(PresenceMember) -> Unit>()
    private val leaveCallbacks = CopyOnWriteArrayList<(PresenceMember) -> Unit>()
    private val updateCallbacks = CopyOnWriteArrayList<(PresenceMember) -> Unit>()
    private val membersCallbacks = CopyOnWriteArrayList<(List<PresenceMember>) -> Unit>()

    fun enter(data: Map<String, Any?>? = null): Presence {
        sendFn(mapOf("action" to "presence.enter", "channel" to channelName, "data" to data))
        return this
    }

    fun leave(): Presence {
        sendFn(mapOf("action" to "presence.leave", "channel" to channelName))
        return this
    }

    fun update(data: Map<String, Any?>): Presence {
        sendFn(mapOf("action" to "presence.update", "channel" to channelName, "data" to data))
        return this
    }

    fun get(): Presence {
        sendFn(mapOf("action" to "presence.get", "channel" to channelName))
        return this
    }

    fun onEnter(cb: (PresenceMember) -> Unit): Presence { enterCallbacks.add(cb); return this }
    fun onLeave(cb: (PresenceMember) -> Unit): Presence { leaveCallbacks.add(cb); return this }
    fun onUpdate(cb: (PresenceMember) -> Unit): Presence { updateCallbacks.add(cb); return this }
    fun onMembers(cb: (List<PresenceMember>) -> Unit): Presence { membersCallbacks.add(cb); return this }

    internal fun handleEvent(action: String, data: Map<String, Any?>) {
        val gson = Gson()
        when (action) {
            "presence.enter" -> {
                val m = gson.fromJson(gson.toJson(data), PresenceMember::class.java)
                enterCallbacks.forEach { it(m) }
            }
            "presence.leave" -> {
                val m = gson.fromJson(gson.toJson(data), PresenceMember::class.java)
                leaveCallbacks.forEach { it(m) }
            }
            "presence.update" -> {
                val m = gson.fromJson(gson.toJson(data), PresenceMember::class.java)
                updateCallbacks.forEach { it(m) }
            }
            "presence.members" -> {
                @Suppress("UNCHECKED_CAST")
                val members = (data["members"] as? List<*>)?.map {
                    gson.fromJson(gson.toJson(it), PresenceMember::class.java)
                } ?: emptyList()
                membersCallbacks.forEach { it(members) }
            }
        }
    }
}

// ─── Channel ────────────────────────────────────────────────

class Channel(val name: String, private val sendFn: (Map<String, Any?>) -> Unit) {
    private val messageCallbacks = CopyOnWriteArrayList<(Any?, MessageMeta) -> Unit>()
    private val historyCallbacks = CopyOnWriteArrayList<(HistoryResult) -> Unit>()
    val presence = Presence(name, sendFn)

    fun subscribe(callback: ((Any?, MessageMeta) -> Unit)? = null): Channel {
        callback?.let { messageCallbacks.add(it) }
        sendFn(mapOf("action" to "subscribe", "channel" to name))
        return this
    }

    fun unsubscribe(): Channel {
        sendFn(mapOf("action" to "unsubscribe", "channel" to name))
        messageCallbacks.clear()
        return this
    }

    fun publish(data: Any?, persist: Boolean? = null): Channel {
        val msg = mutableMapOf<String, Any?>(
            "action" to "publish",
            "channel" to name,
            "data" to data,
            "id" to UUID.randomUUID().toString()
        )
        persist?.let { msg["persist"] = it }
        sendFn(msg)
        return this
    }

    fun history(limit: Int? = null, before: Long? = null, after: Long? = null, direction: String? = null): Channel {
        val opts = mutableMapOf<String, Any?>("action" to "history", "channel" to name)
        limit?.let { opts["limit"] = it }
        before?.let { opts["before"] = it }
        after?.let { opts["after"] = it }
        direction?.let { opts["direction"] = it }
        sendFn(opts)
        return this
    }

    fun onHistory(cb: (HistoryResult) -> Unit): Channel {
        historyCallbacks.add(cb)
        return this
    }

    internal fun handleMessage(data: Any?, meta: MessageMeta) {
        messageCallbacks.forEach { it(data, meta) }
    }

    internal fun handleHistory(result: HistoryResult) {
        historyCallbacks.forEach { it(result) }
    }
}

// ─── PubSub Namespace ──────────────────────────────────────

class PubSubNamespace(private val client: WSocket) {
    fun channel(name: String): Channel = client.channel(name)
}

// ─── Push Client ────────────────────────────────────────────

class PushClient(
    private val baseUrl: String,
    private val token: String,
    private val appId: String
) {
    private val http = OkHttpClient()
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    private fun post(path: String, body: Map<String, Any?>): String {
        val req = Request.Builder()
            .url("$baseUrl/api/push/$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-App-Id", appId)
            .post(gson.toJson(body).toRequestBody(json))
            .build()
        return http.newCall(req).execute().body?.string() ?: ""
    }

    fun registerFCM(deviceToken: String, memberId: String) =
        post("register", mapOf("memberId" to memberId, "platform" to "fcm",
            "subscription" to mapOf("deviceToken" to deviceToken)))

    fun registerAPNs(deviceToken: String, memberId: String) =
        post("register", mapOf("memberId" to memberId, "platform" to "apns",
            "subscription" to mapOf("deviceToken" to deviceToken)))

    fun sendToMember(memberId: String, payload: Map<String, Any?>) =
        post("send", mapOf("memberId" to memberId, "payload" to payload))

    fun broadcast(payload: Map<String, Any?>) =
        post("broadcast", mapOf("payload" to payload))

    fun unregister(memberId: String, platform: String? = null) {
        val body = mutableMapOf<String, Any?>("memberId" to memberId)
        platform?.let { body["platform"] = it }
        val req = Request.Builder()
            .url("$baseUrl/api/push/unregister")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-App-Id", appId)
            .delete(gson.toJson(body).toRequestBody(json))
            .build()
        http.newCall(req).execute()
    }
}

// ─── Client ─────────────────────────────────────────────────

class WSocket(
    private val url: String,
    private val apiKey: String,
    private val options: WSocketOptions = WSocketOptions()
) {
    private val channels = ConcurrentHashMap<String, Channel>()
    private val connected = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private var reconnectAttempts = 0
    private val subscribedChannels = ConcurrentHashMap<String, Boolean>()
    private var lastMessageTs: Long = 0
    private val onConnectCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val onDisconnectCallbacks = CopyOnWriteArrayList<(Int) -> Unit>()
    private val onErrorCallbacks = CopyOnWriteArrayList<(Throwable) -> Unit>()

    val pubsub = PubSubNamespace(this)

    fun onConnect(cb: () -> Unit): WSocket { onConnectCallbacks.add(cb); return this }
    fun onDisconnect(cb: (Int) -> Unit): WSocket { onDisconnectCallbacks.add(cb); return this }
    fun onError(cb: (Throwable) -> Unit): WSocket { onErrorCallbacks.add(cb); return this }

    fun connect(): WSocket {
        val wsUrl = buildString {
            append(url)
            append(if (url.contains("?")) "&" else "?")
            append("key=").append(apiKey)
            options.token?.let { append("&token=").append(it) }
        }

        val request = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                reconnectAttempts = 0

                // Re-subscribe or resume
                if (options.recover && subscribedChannels.isNotEmpty() && lastMessageTs > 0) {
                    val resumeData = mapOf(
                        "channels" to subscribedChannels.keys.toList(),
                        "since" to lastMessageTs
                    )
                    val encoded = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(gson.toJson(resumeData).toByteArray())
                    send(mapOf("action" to "resume", "token" to encoded))
                } else {
                    subscribedChannels.keys.forEach { ch ->
                        send(mapOf("action" to "subscribe", "channel" to ch))
                    }
                }
                onConnectCallbacks.forEach { it() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                onDisconnectCallbacks.forEach { it(code) }
                maybeReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                onErrorCallbacks.forEach { it(t) }
                maybeReconnect()
            }
        })
        return this
    }

    fun disconnect() {
        connected.set(false)
        ws?.close(1000, "Client disconnect")
    }

    fun isConnected() = connected.get()

    fun channel(name: String): Channel {
        return channels.getOrPut(name) { Channel(name) { send(it) } }
    }

    fun configurePush(baseUrl: String, token: String, appId: String) =
        PushClient(baseUrl, token, appId)

    internal fun send(msg: Map<String, Any?>) {
        if (!connected.get()) return
        ws?.send(gson.toJson(msg))
    }

    private fun handleMessage(raw: String) {
        try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val msg: Map<String, Any?> = gson.fromJson(raw, type)
            val action = msg["action"] as? String ?: return
            val channelName = msg["channel"] as? String

            when (action) {
                "message" -> {
                    val ch = channelName?.let { channels[it] } ?: return
                    val ts = (msg["timestamp"] as? Double)?.toLong() ?: System.currentTimeMillis()
                    if (ts > lastMessageTs) lastMessageTs = ts
                    val meta = MessageMeta(
                        id = msg["id"] as? String ?: "",
                        channel = channelName,
                        timestamp = ts
                    )
                    ch.handleMessage(msg["data"], meta)
                }
                "subscribed" -> channelName?.let { subscribedChannels[it] = true }
                "unsubscribed" -> channelName?.let { subscribedChannels.remove(it) }
                "history" -> {
                    val ch = channelName?.let { channels[it] } ?: return
                    @Suppress("UNCHECKED_CAST")
                    val msgs = (msg["messages"] as? List<Map<String, Any?>>)?.map {
                        HistoryMessage(
                            id = it["id"] as? String ?: "",
                            channel = channelName,
                            data = it["data"],
                            publisherId = it["publisherId"] as? String ?: "",
                            timestamp = (it["timestamp"] as? Double)?.toLong() ?: 0,
                            sequence = (it["sequence"] as? Double)?.toLong() ?: 0
                        )
                    } ?: emptyList()
                    ch.handleHistory(HistoryResult(channelName, msgs, msg["hasMore"] == true))
                }
                "presence.enter", "presence.leave", "presence.update", "presence.members" -> {
                    val ch = channelName?.let { channels[it] } ?: return
                    @Suppress("UNCHECKED_CAST")
                    ch.presence.handleEvent(action, msg as Map<String, Any?>)
                }
                "pong" -> {} // heartbeat
                "error" -> {
                    val err = msg["error"] as? String ?: "Unknown error"
                    onErrorCallbacks.forEach { it(RuntimeException(err)) }
                }
            }
        } catch (e: Exception) {
            onErrorCallbacks.forEach { it(e) }
        }
    }

    private fun maybeReconnect() {
        if (!options.autoReconnect) return
        if (reconnectAttempts >= options.maxReconnectAttempts) return
        reconnectAttempts++
        val delay = options.reconnectDelay * reconnectAttempts
        thread {
            Thread.sleep(delay)
            if (!connected.get()) connect()
        }
    }
}

// ─── Factory Function ───────────────────────────────────────

fun createClient(url: String, apiKey: String, options: WSocketOptions = WSocketOptions()) =
    WSocket(url, apiKey, options)
