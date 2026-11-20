package com.custom.astrion.ha

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A template as Home Assistant rendered it: its [text], or why it couldn't ([error]); both null until the first answer. */
data class TemplateText(val text: String? = null, val error: String? = null)

/**
 * Home Assistant templates, rendered by Home Assistant itself (1.5.6, info card): the full Jinja
 * people already write in HA (`state_attr`, filters, `{% if %}`…), and nothing to evaluate on the
 * remote. One `render_template` subscription per distinct template, shared by every card that shows
 * it and kept while one does; Home Assistant sends the text again whenever an entity it reads
 * changes. The WebSocket command needs no admin user (the REST `/api/template` does). Every
 * subscription is sent again after a reconnect ([resubscribe]).
 *
 * [send] writes on the socket, [nextId] gives the next message id, [connected] says whether the
 * socket is authenticated (a template asked before that is sent by [resubscribe]).
 */
class HaTemplates(
    private val send: (JsonObject) -> Unit,
    private val nextId: () -> Int,
    private val connected: () -> Boolean
) {
    private class Sub(val template: String) {
        val text = MutableStateFlow(TemplateText())
        var refs = 0
        var id: Int? = null
    }

    private val subs = HashMap<String, Sub>()
    private val byId = HashMap<Int, Sub>()
    private val oneShots = HashMap<Int, CompletableDeferred<TemplateText>>()

    /** [template] rendered, live; pair it with [release] (the card leaving the screen). */
    @Synchronized
    fun acquire(template: String): StateFlow<TemplateText> {
        val sub = subs.getOrPut(template) { Sub(template) }
        sub.refs++
        if (sub.id == null && connected()) subscribe(sub)
        return sub.text.asStateFlow()
    }

    @Synchronized
    fun release(template: String) {
        val sub = subs[template] ?: return
        if (--sub.refs > 0) return
        subs.remove(template)
        sub.id?.let {
            byId.remove(it)
            unsubscribe(it)
        }
    }

    /** After auth_ok: the old subscriptions died with the old socket. */
    @Synchronized
    fun resubscribe() {
        byId.clear()
        oneShots.values.forEach { it.complete(TemplateText(error = "connection lost")) }
        oneShots.clear()
        subs.values.forEach { subscribe(it) }
    }

    /**
     * Handles a `result` or `event` message when [id] is one of ours; false otherwise (the caller
     * goes on as before).
     */
    fun onMessage(id: Int?, obj: JsonObject): Boolean {
        if (id == null) return false
        val (sub, oneShot) = synchronized(this) { byId[id] to oneShots[id] }
        if (sub == null && oneShot == null) return false
        val answer = answerOf(obj) ?: return true
        sub?.text?.value = answer
        if (oneShot != null) {
            synchronized(this) { oneShots.remove(id) }
            oneShot.complete(answer)
            // A refused template (a `result` with success false) has no subscription to drop.
            if (obj["type"].string() == "event") unsubscribe(id)
        }
        return true
    }

    /** [template] rendered once, for the builder's preview; null while Home Assistant isn't connected. */
    suspend fun renderOnce(template: String): TemplateText? {
        if (!connected()) return null
        val id = nextId()
        val answer = CompletableDeferred<TemplateText>()
        synchronized(this) { oneShots[id] = answer }
        send(request(id, template, reportErrors = true))
        val result = withTimeoutOrNull(ONE_SHOT_TIMEOUT) { answer.await() }
        if (result == null) {
            synchronized(this) { oneShots.remove(id) }
            unsubscribe(id)
        }
        return result ?: TemplateText(error = "no answer from Home Assistant")
    }

    private fun subscribe(sub: Sub) {
        val id = nextId()
        sub.id = id
        byId[id] = sub
        send(request(id, sub.template, reportErrors = false))
    }

    private fun request(id: Int, template: String, reportErrors: Boolean) = buildJsonObject {
        put("id", id)
        put("type", "render_template")
        put("template", template)
        put("timeout", RENDER_TIMEOUT_S)
        if (reportErrors) put("report_errors", true)
    }

    private fun unsubscribe(id: Int) = send(
        buildJsonObject {
            put("id", nextId())
            put("type", "unsubscribe_events")
            put("subscription", id)
        }
    )

    companion object {
        private const val RENDER_TIMEOUT_S = 3.0
        private val ONE_SHOT_TIMEOUT = 6.seconds

        /** True when [s] holds Jinja (`{{ … }}` or `{% … %}`); anything else is shown as it is. */
        fun isTemplate(s: String): Boolean = "{{" in s || "{%" in s

        /**
         * The text or the error in a `render_template` answer; null for a message that carries
         * neither (the subscription's success result, or a warning such as an undefined variable).
         */
        internal fun answerOf(obj: JsonObject): TemplateText? {
            if (obj["type"].string() == "result") {
                if (obj["success"].string() != "false") return null
                val error = obj["error"] as? JsonObject
                return TemplateText(error = error?.get("message").string() ?: "template error")
            }
            val event = obj["event"] as? JsonObject ?: return null
            if (event.containsKey("result")) return TemplateText(text = textOf(event["result"]))
            val error = event["error"].string() ?: return null
            // A warning (an undefined variable…) is followed by the text itself.
            return if (event["level"].string() == "ERROR") TemplateText(error = error) else null
        }

        /** HA renders to native types (a number, a list…): a string as it is, anything else as JSON. */
        internal fun textOf(result: JsonElement?): String = when (result) {
            null, JsonNull -> ""
            is JsonPrimitive -> result.contentOrNull.orEmpty()
            else -> result.toString()
        }

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
