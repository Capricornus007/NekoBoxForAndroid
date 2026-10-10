package io.nekohasekai.sagernet.api

import io.nekohasekai.sagernet.fmt.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

internal class ApiFailure(val code: String, override val message: String) : Exception(message)

internal fun reject(code: String, message: String): Nothing = throw ApiFailure(code, message)

internal fun requireApi(condition: Boolean, message: String) {
    if (!condition) reject("invalid_parameters", message)
}

internal fun JSONObject.keysSet() = keys().asSequence().toSet()
internal fun JSONObject.text(key: String) = (opt(key) as? String)
    ?: reject("invalid_parameters", "$key must be a string")
internal fun JSONObject.flag(key: String, default: Boolean = false): Boolean = if (!has(key)) {
    default
} else {
    (opt(key) as? Boolean)
        ?: reject("invalid_parameters", "$key must be a boolean")
}
internal fun JSONObject.integer(key: String): Long {
    val value = opt(key)
    requireApi(value is Int || value is Long, "$key must be an integer")
    return (value as Number).toLong()
}
internal fun JSONObject.positiveId(key: String = "id") = integer(key).also {
    requireApi(it > 0, "$key must be positive")
}
internal fun JSONObject.objectValue(key: String) = optJSONObject(key)
    ?: reject("invalid_parameters", "$key must be an object")
internal fun JSONObject.arrayValue(key: String) = optJSONArray(key)
    ?: reject("invalid_parameters", "$key must be an array")
internal fun JSONObject.confirm() {
    if (!flag("confirm")) reject("confirmation_required", "Repeat with confirm=true to authorize this change")
}
internal fun JSONObject.secrets() {
    if (!flag("includeSecrets")) reject("secrets_required", "This operation requires includeSecrets=true")
}
internal fun jsonArray(values: Iterable<Any?>) = JSONArray().apply { values.forEach { put(it) } }
internal fun JSONArray.objects() = (0 until length()).map {
    optJSONObject(it) ?: reject("invalid_parameters", "Expected an array of objects")
}

internal object ApiBean {
    private fun fields(type: Class<*>) = type.fields.filter {
        !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && !Modifier.isTransient(it.modifiers)
    }.associateBy { it.name }

    fun encode(bean: Serializable) = JSONObject().apply {
        fields(bean.javaClass).forEach { (name, field) -> put(name, JSONObject.wrap(field.get(bean))) }
    }

    fun describe(bean: Serializable) = JSONObject().apply {
        fields(bean.javaClass).forEach { (name, field) ->
            put(name, JSONObject().put("type", typeName(field.genericType)).put("nullable", !field.type.isPrimitive).put("default", JSONObject.wrap(field.get(bean))))
        }
    }

    fun <T : Serializable> patch(bean: T, changes: JSONObject): T {
        val fields = fields(bean.javaClass)
        requireApi(changes.keysSet().all { it in fields }, "Unknown configuration field")
        // Decode the entire patch before touching the caller's object.
        val values = changes.keysSet().map { name ->
            val field = fields.getValue(name)
            field to if (changes.isNull(name) && !field.type.isPrimitive) null else decode(changes.get(name), field.genericType)
        }
        values.forEach { (field, value) -> field.set(bean, value) }
        bean.initializeDefaultValues()
        return bean
    }

    private fun typeName(type: Type): String = when (type) {
        String::class.java -> "string"
        Boolean::class.java, java.lang.Boolean::class.java -> "boolean"
        Int::class.java, java.lang.Integer::class.java -> "integer"
        Long::class.java, java.lang.Long::class.java -> "integer"
        is ParameterizedType -> "array<${typeName(type.actualTypeArguments.single())}>"
        else -> reject("unsupported_field", "Unsupported configuration field type")
    }

    private fun decode(value: Any, type: Type): Any = when (type) {
        String::class.java -> (value as? String)?.also { requireApi(it.length <= 1_048_576, "String is too long") }
            ?: reject("invalid_parameters", "Expected a string")

        Boolean::class.java, java.lang.Boolean::class.java ->
            value as? Boolean
                ?: reject("invalid_parameters", "Expected a boolean")

        Int::class.java, java.lang.Integer::class.java -> {
            requireApi(value is Int || value is Long, "Expected an integer")
            val number = (value as Number).toLong()
            requireApi(number in Int.MIN_VALUE..Int.MAX_VALUE, "Integer is out of range")
            number.toInt()
        }

        Long::class.java, java.lang.Long::class.java -> {
            requireApi(value is Int || value is Long, "Expected an integer")
            (value as Number).toLong()
        }

        is ParameterizedType -> {
            requireApi(type.rawType == List::class.java, "Unsupported collection type")
            val array = value as? JSONArray ?: reject("invalid_parameters", "Expected an array")
            requireApi(array.length() <= 10_000, "Array is too large")
            (0 until array.length()).map { decode(array.get(it), type.actualTypeArguments.single()) }.toMutableList()
        }

        else -> reject("unsupported_field", "Unsupported configuration field type")
    }
}
