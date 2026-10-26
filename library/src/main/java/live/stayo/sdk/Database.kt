package live.stayo.sdk

import kotlinx.serialization.json.*

class DbResponse(val data: JsonElement, val contentRange: String?)
class Table internal constructor(private val client: StayoClient, private val table: String, private val schema: String) {
    fun select(columns: String = "*"): Query = Query(client, table, schema, "GET", null, listOf("select" to columns))
    fun insert(rows: JsonElement): Query = Query(client, table, schema, "POST", rows)
    fun upsert(rows: JsonElement, onConflict: String? = null): Query = Query(client, table, schema, "POST", rows,
        onConflict?.let { listOf("on_conflict" to it) } ?: emptyList(), mapOf("Prefer" to "return=representation,resolution=merge-duplicates"))
    fun update(values: JsonObject): Query = Query(client, table, schema, "PATCH", values)
    fun delete(): Query = Query(client, table, schema, "DELETE", null)
}
/** Immutable PostgREST builder. Each execute dispatches once. RLS stays authoritative on the server. */
class Query internal constructor(
    private val client: StayoClient, private val table: String, private val schema: String,
    private val method: String, private val body: JsonElement?,
    private val parameters: List<Pair<String, String>> = emptyList(), private val headers: Map<String, String> = emptyMap(),
) {
    fun filter(column: String, operator: String, value: String): Query {
        require(operator in setOf("eq", "neq", "gt", "gte", "lt", "lte", "like", "ilike", "is", "in", "cs", "cd", "ov"))
        return copy(parameters = parameters + (identifier(column) to "$operator.$value"))
    }
    fun eq(column: String, value: Any?): Query = filter(column, "eq", value?.toString() ?: "null")
    fun order(column: String, ascending: Boolean = true): Query = copy(parameters = parameters + ("order" to "${identifier(column)}.${if (ascending) "asc" else "desc"}"))
    fun limit(count: Int): Query { require(count >= 0); return copy(parameters = parameters + ("limit" to count.toString())) }
    fun range(from: Int, to: Int): Query {
        require(from >= 0 && to >= from)
        return copy(headers = headers + mapOf("Range-Unit" to "items", "Range" to "$from-$to"))
    }
    suspend fun execute(): DbResponse {
        val result = client.request(method, listOf("rest", "v1", table), body, parameters,
            mapOf("Accept-Profile" to schema, "Content-Profile" to schema, "Prefer" to "return=representation") + headers)
        return DbResponse(result.asJson(), result.contentRange)
    }
    private fun copy(parameters: List<Pair<String, String>> = this.parameters, headers: Map<String, String> = this.headers) =
        Query(client, table, schema, method, body, parameters, headers)
}
