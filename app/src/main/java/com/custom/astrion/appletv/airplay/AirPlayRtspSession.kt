package com.custom.astrion.appletv.airplay

/**
 * The RTSP session proper, used once pair-verify has armed control-connection encryption:
 * SETUP/RECORD/feedback, each carrying `CSeq` (incrementing per request), `DACP-ID`,
 * `Active-Remote` and `Client-Instance` (the same value as `DACP-ID` — confirmed against
 * pyatv's `RtspSession.exchange`, which sends all four on every request). SETUP/RECORD default to
 * a fixed `rtsp://<our-local-ip>/<session-id>` URI; `feedback` targets a plain `/feedback` path
 * instead — also matching pyatv exactly, down to that asymmetry.
 */
internal class AirPlayRtspSession(private val conn: AirPlayConnection) {
    companion object {
        private const val USER_AGENT = "AirPlay/550.10"
    }

    private var cSeq = 0
    private val dacpId: String = (0..0xFFFFFFFFFFFFFFFL).random().toString(16).uppercase()
    private val activeRemote: Int = (0..Int.MAX_VALUE).random()
    private val sessionId: Long = (0..0xFFFFFFFFL).random()
    private val sessionUri: String by lazy { "rtsp://${conn.localAddress}/$sessionId" }

    private fun exchange(method: String, uri: String, contentType: String? = null, body: ByteArray = ByteArray(0)): RtspHttp.Response {
        val headers =
            linkedMapOf(
                "CSeq" to (cSeq++).toString(),
                "DACP-ID" to dacpId,
                "Active-Remote" to activeRemote.toString(),
                "Client-Instance" to dacpId
            )
        return conn.exchange(method, uri, USER_AGENT, headers, contentType, body)
    }

    /** `SETUP` with a bplist body, returning the response's body parsed the same way. */
    fun setup(body: BinaryPlist.Value): BinaryPlist.Value {
        val response = exchange("SETUP", sessionUri, contentType = "application/x-apple-binary-plist", body = BinaryPlist.encode(body))
        requireOk(response, "SETUP")
        return bodyAsPlist(response)
    }

    fun record() {
        requireOk(exchange("RECORD", sessionUri), "RECORD")
    }

    fun feedback() {
        requireOk(exchange("POST", "/feedback"), "feedback")
    }

    private fun bodyAsPlist(response: RtspHttp.Response): BinaryPlist.Value =
        if (response.body.isEmpty()) BinaryPlist.Value.Dict(LinkedHashMap()) else BinaryPlist.decode(response.body)

    private fun requireOk(response: RtspHttp.Response, what: String) {
        if (response.status !in 200..299) throw AirPlayException("$what failed: RTSP ${response.status} ${response.reasonPhrase}")
    }
}
