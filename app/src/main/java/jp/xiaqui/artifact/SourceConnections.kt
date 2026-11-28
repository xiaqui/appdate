package jp.xiaqui.artifact

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SourceConnection(
    val key: String,
    val provider: String,
    val server: String,
    val owner: String,
    val label: String = owner
)

internal class SourceConnections(context: Context) {
    private val prefs = context.getSharedPreferences("source-connections-v1", Context.MODE_PRIVATE)

    fun all(): List<SourceConnection> = synchronized(repoDataLock) {
        val rows = JSONArray(prefs.getString("connections", "[]"))
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            SourceConnection(row.getString("key"), row.getString("provider"), row.getString("server"),
                row.getString("owner"), row.optString("label", row.getString("owner")))
        }
    }

    fun saveForgejo(serverRaw: String, ownerRaw: String): SourceConnection = synchronized(repoDataLock) {
        val server = normalizeServer(serverRaw)
        val owner = ownerRaw.trim().also {
            if (!it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || it in listOf(".", ".."))
                throw ImportFailure("Forgejoのowner名を確認してください。")
        }
        val key = "forgejo|$server|${owner.lowercase()}"
        val connection = SourceConnection(key, PROVIDER_FORGEJO, server, owner)
        val rows = all().filterNot { it.key == key } + connection
        val json = JSONArray(); rows.forEach { row ->
            json.put(JSONObject().put("key", row.key).put("provider", row.provider).put("server", row.server)
                .put("owner", row.owner).put("label", row.label))
        }
        if (!prefs.edit().putString("connections", json.toString()).commit())
            throw ImportFailure("Forgejo接続先を保存できません。既存設定は保持しています。")
        connection
    }

    fun remove(key: String) = synchronized(repoDataLock) {
        val json = JSONArray(); all().filterNot { it.key == key }.forEach { row ->
            json.put(JSONObject().put("key", row.key).put("provider", row.provider).put("server", row.server)
                .put("owner", row.owner).put("label", row.label))
        }
        if (!prefs.edit().putString("connections", json.toString()).commit())
            throw ImportFailure("Forgejo接続先を解除できません。")
    }
}
