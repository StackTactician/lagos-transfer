package com.lagostransfer.data

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

object SecureStore {
    @Volatile private var cachedPrefs: SharedPreferences? = null
    private fun prefs(ctx: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).setRequestStrongBoxBacked(false).build()
        val p = EncryptedSharedPreferences.create(
            ctx, "secure_accounts", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        cachedPrefs = p
        return p
    }
    fun saveAccount(ctx: Context, username: String, password: String, pin: String?) {
        val c = mapOf("u_$username" to username, "p_$username" to password, "pin_$username" to (pin ?: ""))
        prefs(ctx).edit().apply { c.forEach { (k, v) -> putString(k, v) } }.apply()
    }
    fun loadAccounts(ctx: Context): List<Triple<String,String,String?>> {
        val p = prefs(ctx)
        return p.all.keys.filter { it.startsWith("u_") }.mapNotNull { k ->
            val u = k.removePrefix("u_")
            val pass = p.getString("p_$u", null) ?: return@mapNotNull null
            val pin = p.getString("pin_$u", null)?.ifBlank { null }
            Triple(u, pass, pin)
        }
    }
    fun removeAccount(ctx: Context, username: String) {
        prefs(ctx).edit().remove("u_$username").remove("p_$username").remove("pin_$username").apply()
        if (prefs(ctx).all.keys.none { it.startsWith("u_") }) cachedPrefs = null
    }
    fun saveCookies(ctx: Context, username: String, map: Map<String, String>) {
        prefs(ctx).edit().putString("ck_$username", map.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }
    fun loadCookies(ctx: Context, username: String): Map<String, String> {
        val raw = prefs(ctx).getString("ck_$username", null) ?: return emptyMap()
        return raw.split(";").filter { "=" in it }.map {
            val (k, v) = it.split("=", limit = 2); k.trim() to v.trim()
        }.toMap()
    }
    fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}

data class LogEntry(val ts: Long, val account: String, val recipient: String, val amount: Long, val status: String, val transferred: Int, val remaining: Int, val amountLeft: Long)

class LogDb(ctx: Context) : SQLiteOpenHelper(ctx, "transfer_logs.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE logs(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, account TEXT, username_hash TEXT, recipient TEXT, amount INTEGER, status TEXT, transferred INTEGER, remaining INTEGER, amount_left INTEGER)")
        db.execSQL("CREATE INDEX idx_recipient ON logs(recipient)")
        db.execSQL("CREATE INDEX idx_account ON logs(account)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_recipient ON logs(recipient)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_account ON logs(account)")
        }
    }
    fun insert(account: String, recipient: String, amount: Long, status: String, transferred: Int, remaining: Int, amountLeft: Long) {
        writableDatabase.execSQL(
            "INSERT INTO logs(ts,account,username_hash,recipient,amount,status,transferred,remaining,amount_left) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(System.currentTimeMillis(), account, SecureStore.hash(recipient), recipient, amount, status, transferred, remaining, amountLeft)
        )
    }
    fun all(account: String): List<String> {
        val c = readableDatabase.rawQuery("SELECT ts,recipient,amount,status,transferred,remaining,amount_left FROM logs WHERE account=? ORDER BY id DESC LIMIT 100", arrayOf(account))
        val out = mutableListOf<String>()
        while (c.moveToNext()) {
            val total = c.getInt(4) + c.getInt(5)
            out.add("${c.getInt(4)}/$total · ₦${c.getLong(2)} → @${c.getString(1)} · ${c.getString(3)}")
        }
        c.close(); return out
    }
    fun history(search: String?, filterStatus: String?, account: String? = null): List<LogEntry> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!account.isNullOrBlank() && account != "All") {
            where.add("account = ?")
            args.add(account)
        }
        if (!search.isNullOrBlank()) {
            where.add("(recipient LIKE ? OR account LIKE ?)")
            args.add("%$search%"); args.add("%$search%")
        }
        if (!filterStatus.isNullOrBlank() && filterStatus != "All") {
            where.add("status = ?")
            args.add(filterStatus)
        }
        val sql = "SELECT ts,account,recipient,amount,status,transferred,remaining,amount_left FROM logs" +
                (if (where.isNotEmpty()) " WHERE " + where.joinToString(" AND ") else "") +
                " ORDER BY id DESC LIMIT 200"
        val c = readableDatabase.rawQuery(sql, args.toTypedArray())
        val out = mutableListOf<LogEntry>()
        while (c.moveToNext()) {
            out.add(LogEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getInt(5), c.getInt(6), c.getLong(7)))
        }
        c.close(); return out
    }
}
