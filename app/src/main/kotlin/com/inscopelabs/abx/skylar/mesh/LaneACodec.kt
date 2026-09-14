package com.inscopelabs.abx.skylar.mesh

import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.envelope.RequestEnvelope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialization codec for Lane A network messages and canonical RequestEnvelopes.
 *
 * Implements Module role per AGENTS.md §4 (pure serialization / deserialization).
 */
object LaneACodec {
    private const val TAG = "LaneACodec"

    fun encodeEnvelope(envelope: RequestEnvelope): String {
        val json = JSONObject().apply {
            put("envelopeVersion", envelope.envelopeVersion)
            put("callerId", envelope.callerId)
            put("capability", envelope.capability)
            put("params", mapToJson(envelope.params))
            put("nonce", envelope.nonce)
            put("issuedAt", envelope.issuedAt)
            put("expiresAt", envelope.expiresAt)
            put("workflowHash", envelope.workflowHash)
            put("signature", envelope.signature)
            if (envelope.scope != null) {
                put("scope", envelope.scope)
            }
        }
        return json.toString()
    }

    fun decodeEnvelope(jsonString: String): RequestEnvelope {
        return try {
            val json = JSONObject(jsonString)
            val paramsObj = json.optJSONObject("params")
            val paramsMap = if (paramsObj != null) jsonToMap(paramsObj) else emptyMap()

            RequestEnvelope(
                envelopeVersion = json.optInt("envelopeVersion", RequestEnvelope.CURRENT_ENVELOPE_VERSION),
                callerId = json.getString("callerId"),
                capability = json.getString("capability"),
                params = paramsMap,
                nonce = json.getString("nonce"),
                issuedAt = json.getLong("issuedAt"),
                expiresAt = json.getLong("expiresAt"),
                workflowHash = json.getString("workflowHash"),
                signature = json.getString("signature"),
                scope = if (json.has("scope") && !json.isNull("scope")) json.getString("scope") else null
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to decode RequestEnvelope from JSON: ${e.message}", e)
            throw IllegalArgumentException("Malformed RequestEnvelope JSON: ${e.message}", e)
        }
    }

    fun encodeRequest(request: LaneARequest): String {
        val json = JSONObject().apply {
            put("envelopeJson", request.envelopeJson)
            if (request.callerTransportId != null) put("callerTransportId", request.callerTransportId)
            if (request.transportAuthToken != null) put("transportAuthToken", request.transportAuthToken)
            if (request.metadata.isNotEmpty()) {
                val metaObj = JSONObject()
                request.metadata.forEach { (k, v) -> metaObj.put(k, v) }
                put("metadata", metaObj)
            }
        }
        return json.toString()
    }

    fun decodeRequest(jsonString: String): LaneARequest {
        return try {
            val json = JSONObject(jsonString)
            val metadataMap = mutableMapOf<String, String>()
            if (json.has("metadata")) {
                val metaObj = json.getJSONObject("metadata")
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    metadataMap[key] = metaObj.getString(key)
                }
            }
            LaneARequest(
                envelopeJson = json.getString("envelopeJson"),
                callerTransportId = if (json.has("callerTransportId")) json.getString("callerTransportId") else null,
                transportAuthToken = if (json.has("transportAuthToken")) json.getString("transportAuthToken") else null,
                metadata = metadataMap
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to decode LaneARequest from JSON: ${e.message}", e)
            throw IllegalArgumentException("Malformed LaneARequest JSON: ${e.message}", e)
        }
    }

    fun encodeResponse(response: LaneAResponse): String {
        val json = JSONObject().apply {
            put("success", response.success)
            put("statusCode", response.statusCode)
            put("status", response.status)
            if (response.data != null) put("data", mapToJson(response.data))
            if (response.errorMessage != null) put("errorMessage", response.errorMessage)
            if (response.errorCode != null) put("errorCode", response.errorCode)
            if (response.target != null) put("target", response.target)
            put("executionTimeMs", response.executionTimeMs)
        }
        return json.toString()
    }

    fun decodeResponse(jsonString: String): LaneAResponse {
        return try {
            val json = JSONObject(jsonString)
            val dataObj = json.optJSONObject("data")
            val dataMap = if (dataObj != null) jsonToMap(dataObj) else null

            LaneAResponse(
                success = json.getBoolean("success"),
                statusCode = json.getInt("statusCode"),
                status = json.getString("status"),
                data = dataMap,
                errorMessage = if (json.has("errorMessage") && !json.isNull("errorMessage")) json.getString("errorMessage") else null,
                errorCode = if (json.has("errorCode") && !json.isNull("errorCode")) json.getString("errorCode") else null,
                target = if (json.has("target") && !json.isNull("target")) json.getString("target") else null,
                executionTimeMs = json.optLong("executionTimeMs", 0L)
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to decode LaneAResponse from JSON: ${e.message}", e)
            throw IllegalArgumentException("Malformed LaneAResponse JSON: ${e.message}", e)
        }
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((k, v) in map) {
            when (v) {
                null -> json.put(k, JSONObject.NULL)
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") json.put(k, mapToJson(v as Map<String, Any?>))
                is List<*> -> json.put(k, listToJson(v))
                else -> json.put(k, v)
            }
        }
        return json
    }

    private fun listToJson(list: List<*>): JSONArray {
        val array = JSONArray()
        for (item in list) {
            when (item) {
                null -> array.put(JSONObject.NULL)
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") array.put(mapToJson(item as Map<String, Any?>))
                is List<*> -> array.put(listToJson(item))
                else -> array.put(item)
            }
        }
        return array
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = when (value) {
                JSONObject.NULL -> null
                is JSONObject -> jsonToMap(value)
                is JSONArray -> jsonToList(value)
                else -> value
            }
        }
        return map
    }

    private fun jsonToList(array: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            list.add(
                when (value) {
                    JSONObject.NULL -> null
                    is JSONObject -> jsonToMap(value)
                    is JSONArray -> jsonToList(value)
                    else -> value
                }
            )
        }
        return list
    }
}
