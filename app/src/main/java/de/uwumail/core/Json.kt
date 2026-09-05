package de.uwumail.core

import org.json.JSONArray
import org.json.JSONObject

object Json {

    fun encodeHeaders(headers: Map<String, List<String>>): String {
        val root = JSONObject()
        headers.forEach { (name, values) ->
            root.put(name.lowercase(), JSONArray().apply { values.forEach { put(it) } })
        }
        return root.toString()
    }

    fun decodeHeaders(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { key ->
                    val values = when (val raw = root.get(key)) {
                        is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                        else -> listOf(raw.toString())
                    }
                    put(key.lowercase(), values)
                }
            }
        }.getOrDefault(emptyMap())
    }
}

/** Comma-joined address list used throughout the message entities. */
fun String.toAddressList(): List<String> =
    split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }

fun List<String>.joinAddresses(): String = joinToString(",")
