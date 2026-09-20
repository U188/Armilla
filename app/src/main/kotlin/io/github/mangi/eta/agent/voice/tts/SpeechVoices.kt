package io.github.mangi.eta.agent.voice.tts

internal object SpeechVoices {
    fun shouldReplaceStoredVoice(
        cloud: Boolean,
        providerId: String,
        providerReady: Boolean,
        storedVoice: String,
        catalogIds: Collection<String>,
    ): Boolean {
        if (!cloud || catalogIds.isEmpty()) return false
        // Providers load asynchronously. A transient OpenAI catalog must not overwrite
        // a saved Xiaomi/Doubao voice with the first fallback, usually "默认".
        if (providerId.isNotBlank() && !providerReady) return false
        if (storedVoice.isBlank()) return true
        return storedVoice !in catalogIds
    }

    fun catalog(engine: SpeechEngine, model: String = ""): List<SpeechVoice> = when (engine) {
        SpeechEngine.DOUBAO -> DoubaoVoices.catalog
        SpeechEngine.OPENAI -> openai
        SpeechEngine.MIMO -> mimo
        SpeechEngine.MINIMAX -> minimax
        SpeechEngine.STEP -> step
        SpeechEngine.QWEN -> qwen(model)
        SpeechEngine.GROQ -> groq
        SpeechEngine.XAI -> xai
        SpeechEngine.GEMINI -> gemini
        SpeechEngine.ELEVENLABS -> elevenlabs
        SpeechEngine.FISH -> emptyList()
        SpeechEngine.COSYVOICE -> cosyVoice(model)
        SpeechEngine.MOSS -> moss
    }

    private val openai = listOf(
        SpeechVoice("alloy", "Alloy"),
        SpeechVoice("echo", "Echo"),
        SpeechVoice("fable", "Fable"),
        SpeechVoice("onyx", "Onyx"),
        SpeechVoice("nova", "Nova"),
        SpeechVoice("shimmer", "Shimmer"),
    )
    private val mimo = listOf(
        SpeechVoice("mimo_default", "默认"),
        SpeechVoice("冰糖", "冰糖"),
        SpeechVoice("茉莉", "茉莉"),
        SpeechVoice("苏打", "苏打"),
        SpeechVoice("白桦", "白桦"),
        SpeechVoice("Mia", "Mia"),
        SpeechVoice("Chloe", "Chloe"),
        SpeechVoice("Milo", "Milo"),
        SpeechVoice("Dean", "Dean"),
    )
    private val minimax = listOf(
        SpeechVoice("female-shaonv", "少女"),
        SpeechVoice("female-yujie", "御姐"),
        SpeechVoice("female-chengshu", "成熟女声"),
        SpeechVoice("female-tianmei", "甜美"),
        SpeechVoice("male-qn-qingse", "青涩男声"),
        SpeechVoice("male-qn-jingying", "精英男声"),
        SpeechVoice("male-qn-badao", "霸道男声"),
        SpeechVoice("male-qn-daxuesheng", "大学生"),
        SpeechVoice("audiobook_male_1", "有声书男"),
        SpeechVoice("audiobook_female_1", "有声书女"),
        SpeechVoice("cartoon_pig", "卡通"),
    )
    private val step = listOf(
        SpeechVoice("elegantgentle-female", "气质温婉"),
        SpeechVoice("livelybreezy-female", "活力轻快"),
        SpeechVoice("jingdiannvsheng", "经典女声"),
        SpeechVoice("wenroushunv", "温柔熟女"),
        SpeechVoice("tianmeinvsheng", "甜美女声"),
        SpeechVoice("qingchunshaonv", "清纯少女"),
        SpeechVoice("cixingnansheng", "磁性男声"),
        SpeechVoice("wenrounansheng", "温柔男声"),
        SpeechVoice("yuanqinansheng", "元气男声"),
        SpeechVoice("zhengpaiqingnian", "正派青年"),
        SpeechVoice("ruyananshi", "儒雅男士"),
        SpeechVoice("boyinnansheng", "播音男声"),
    )
    private val groq = listOf(
        SpeechVoice("austin", "Austin"),
        SpeechVoice("natalie", "Natalie"),
        SpeechVoice("kailin", "Kailin"),
    )
    private val xai = listOf(
        SpeechVoice("eve", "Eve"),
        SpeechVoice("ara", "Ara"),
        SpeechVoice("rex", "Rex"),
        SpeechVoice("sal", "Sal"),
        SpeechVoice("leo", "Leo"),
    )
    private val gemini = listOf(
        SpeechVoice("Kore", "Kore"),
        SpeechVoice("Puck", "Puck"),
        SpeechVoice("Charon", "Charon"),
        SpeechVoice("Fenrir", "Fenrir"),
        SpeechVoice("Aoede", "Aoede"),
        SpeechVoice("Leda", "Leda"),
        SpeechVoice("Orus", "Orus"),
        SpeechVoice("Zephyr", "Zephyr"),
    )
    private val elevenlabs = listOf(
        SpeechVoice("JBFqnCBsd6RMkjVDRZzb", "George"),
    )

    private val moss = listOf(
        SpeechVoice("default", "默认"),
        SpeechVoice("female", "女声"),
        SpeechVoice("male", "男声"),
    )

    private fun cosyVoice(model: String): List<SpeechVoice> {
        val id = model.lowercase()
        return if ("2" in id || "v2" in id) cosyVoice2 + cosyVoice1 else cosyVoice1
    }

    private val cosyVoice1 = listOf(
        SpeechVoice("中文女", "中文女"),
        SpeechVoice("中文男", "中文男"),
        SpeechVoice("日语男", "日语男"),
        SpeechVoice("粤语女", "粤语女"),
        SpeechVoice("韩语女", "韩语女"),
        SpeechVoice("四川话女", "四川女"),
        SpeechVoice("上海话女", "上海女"),
    )

    private val cosyVoice2 = listOf(
        SpeechVoice("longxiaochun_v2", "龙小淳"),
        SpeechVoice("longxiaoxia_v2", "龙小夏"),
        SpeechVoice("longxiaocheng_v2", "龙小诚"),
        SpeechVoice("longxiaobai_v2", "龙小白"),
        SpeechVoice("longwan_v2", "龙婉"),
        SpeechVoice("longcheng_v2", "龙橙"),
        SpeechVoice("longhua_v2", "龙华"),
        SpeechVoice("loongstella", "Stella"),
        SpeechVoice("loongbella", "Bella"),
    )

    private fun qwen(model: String): List<SpeechVoice> {
        val id = model.lowercase()
        return if ("plus" in id) {
            listOf(SpeechVoice("longanlingxin", "灵心"), SpeechVoice("longanlufeng", "陆风"))
        } else {
            listOf(
                SpeechVoice("longanhuan_v3.6", "欢"),
                SpeechVoice("longanfengyue", "风月"),
                SpeechVoice("longanyuanfei", "远飞"),
                SpeechVoice("longanlingxi", "灵犀"),
                SpeechVoice("longanxiaoxin", "小欣"),
                SpeechVoice("loongmary", "Mary"),
                SpeechVoice("loongjohn", "John"),
            )
        }
    }
}
