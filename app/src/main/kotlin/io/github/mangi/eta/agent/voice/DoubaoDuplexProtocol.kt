package io.github.mangi.eta.agent.voice

import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/** API-key authentication must not be mixed with legacy TTS app/access headers. */
internal object DoubaoDuplexProtocol {
    fun request(apiKey: String): Request = Request.Builder()
        .url("wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue")
        .header("X-Api-Key", apiKey)
        .header("X-Api-Connect-Id", UUID.randomUUID().toString())
        .build()

    fun sessionCreate(voice: String, instructions: String): JSONObject = JSONObject()
        .put("type", "session.create")
        .put("event_id", UUID.randomUUID().toString())
        .put(
            "extension",
            JSONObject().put("asr", JSONObject().put("extra",
                JSONObject().put("enable_asr_twopass", true),
            )),
        )
        .put(
            "session",
            JSONObject()
                .put("model", "1.2.6.1")
                .put("instructions", instructions)
                .put(
                    "audio",
                    JSONObject()
                        .put(
                            "input",
                            JSONObject().put(
                                "format",
                                JSONObject().put("type", "pcm").put("rate", INPUT_RATE),
                            ),
                        )
                        .put(
                            "output",
                            JSONObject()
                                .put(
                                    "format",
                                    JSONObject().put("type", "pcm").put("rate", OUTPUT_RATE),
                                )
                                .put("voice", voice),
                        ),
                ),
        )

    private const val INPUT_RATE = 16_000
    private const val OUTPUT_RATE = 24_000
}
