package com.macro.engine

import org.json.JSONObject
import java.io.File

/**
 * Per-macro configuration stored as JSON sidecar files alongside .bin recordings.
 */
data class MacroConfig(
    val name: String = "",
    val startDelayMs: Int = 1000,
    val repeatCount: Int = 1,       // 0 = infinite
    val speed: Float = 1.0f,
    val durationMs: Long = 0,       // total recording duration
    val trigger: TriggerConfig? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("startDelayMs", startDelayMs)
        put("repeatCount", repeatCount)
        put("speed", speed.toDouble())
        put("durationMs", durationMs)
        trigger?.let { put("trigger", it.toJson()) }
    }

    companion object {
        /** Get the sidecar .json path for a given .bin file */
        fun configFileFor(binFile: File): File =
            File(binFile.parent, binFile.nameWithoutExtension + ".json")

        /** Load config from sidecar, or return defaults */
        fun load(binFile: File): MacroConfig {
            val configFile = configFileFor(binFile)
            if (!configFile.exists()) return MacroConfig(name = binFile.nameWithoutExtension)
            return try {
                fromJson(JSONObject(configFile.readText()))
            } catch (e: Exception) {
                MacroConfig(name = binFile.nameWithoutExtension)
            }
        }

        /** Save config to sidecar .json */
        fun save(binFile: File, config: MacroConfig) {
            val configFile = configFileFor(binFile)
            configFile.writeText(config.toJson().toString(2))
        }

        /** Delete sidecar .json when macro is deleted */
        fun delete(binFile: File) {
            val configFile = configFileFor(binFile)
            if (configFile.exists()) configFile.delete()
        }

        fun fromJson(json: JSONObject): MacroConfig = MacroConfig(
            name = json.optString("name", ""),
            startDelayMs = json.optInt("startDelayMs", 1000),
            repeatCount = json.optInt("repeatCount", 1),
            speed = json.optDouble("speed", 1.0).toFloat(),
            durationMs = json.optLong("durationMs", 0),
            trigger = if (json.has("trigger")) TriggerConfig.fromJson(json.getJSONObject("trigger")) else null
        )
    }
}

data class TriggerConfig(
    val enabled: Boolean = false,
    val x: Int = 200,
    val y: Int = 400,
    val shape: String = "circle",       // circle | square | rounded_rect
    val opacity: Float = 0.8f,
    val mode: String = "tap",           // tap | hold
    val locked: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("x", x)
        put("y", y)
        put("shape", shape)
        put("opacity", opacity.toDouble())
        put("mode", mode)
        put("locked", locked)
    }

    companion object {
        fun fromJson(json: JSONObject): TriggerConfig = TriggerConfig(
            enabled = json.optBoolean("enabled", false),
            x = json.optInt("x", 200),
            y = json.optInt("y", 400),
            shape = json.optString("shape", "circle"),
            opacity = json.optDouble("opacity", 0.8).toFloat(),
            mode = json.optString("mode", "tap"),
            locked = json.optBoolean("locked", false)
        )
    }
}
