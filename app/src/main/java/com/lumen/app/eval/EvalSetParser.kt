package com.lumen.app.eval

import org.json.JSONArray
import org.json.JSONException

/**
 * Parses the developer-authored evaluation set. Robust by spec: BOM-tolerant,
 * per-entry validation with indexed reasons, never throws.
 */
// @spec SEARCH-EVAL-002
object EvalSetParser {

    fun parse(text: String): EvalParse {
        val body = text.removePrefix("﻿").trim()
        if (body.isEmpty()) return EvalParse(emptyList(), listOf("File is empty."))

        val array = try {
            JSONArray(body)
        } catch (_: JSONException) {
            return EvalParse(emptyList(), listOf("Not a JSON array of entries."))
        }

        val entries = mutableListOf<EvalEntry>()
        val invalid = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                invalid += "Entry $i: not an object."
                continue
            }
            val query = obj.optString("query").trim()
            if (query.isEmpty()) {
                invalid += "Entry $i: missing or blank \"query\"."
                continue
            }
            val expectedFile = obj.optString("expectedFile").trim()
            if (expectedFile.isEmpty()) {
                invalid += "Entry $i: missing or blank \"expectedFile\"."
                continue
            }
            val expectedPage = if (obj.has("expectedPage")) {
                val page = obj.optInt("expectedPage", Int.MIN_VALUE)
                if (page < 1) {
                    invalid += "Entry $i: \"expectedPage\" must be 1 or greater (display page numbers)."
                    continue
                }
                page
            } else {
                null
            }
            val tag = obj.optString("tag").trim().ifEmpty { "untagged" }
            entries += EvalEntry(query, expectedFile, expectedPage, tag)
        }
        return EvalParse(entries, invalid)
    }
}
