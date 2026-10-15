package com.lagostransfer.data

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

object SecureStore {
    private fun prefs(ctx: Context): SharedPreferences {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "secure_accounts", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    fun saveAccount(ctx: Context, username: String, password: String, pin: String?) {
        prefs(ctx).edit()
            .putString("u_$username", username)
            .putString("p_$username", password) // encrypted at rest via Tink
            .putString("pin_$username", pin ?: "")
            .apply()
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
    }
    fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}

class LogDb(ctx: Context) : SQLiteOpenHelper(ctx, "transfer_logs.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE logs(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, account TEXT, username_hash TEXT, recipient TEXT, amount INTEGER, status TEXT, transferred INTEGER, remaining INTEGER, amount_left INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
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
            val rec = c.getString(1)
            val amt = c.getLong(2)
            val st = c.getString(3)
            val tr = c.getInt(4)
            val rem = c.getInt(5)
            val left = c.getLong(6)
            out.add("$st ₦$amt → @$rec · $tr done, $rem left, ₦$left left")
        }
        c.close(); return out
    }
}
