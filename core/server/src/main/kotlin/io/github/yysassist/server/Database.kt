package io.github.yysassist.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** One SQLite connection; every unit of work runs in a serialized transaction. */
class Database(path: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON"); it.execute("PRAGMA busy_timeout = 5000") }
        SCHEMA.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        connection.autoCommit = false
        connection.commit()
    }

    @Synchronized fun <T> tx(block: Database.() -> T): T = try {
        block().also { connection.commit() }
    } catch (error: Throwable) { connection.rollback(); throw error }

    fun update(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
    }

    fun <T> single(sql: String, vararg args: Any?, row: (ResultSet) -> T): T? = query(sql, *args, row = row).firstOrNull()

    @Synchronized override fun close() = connection.close()

    private companion object {
        val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE, email_verified INTEGER NOT NULL DEFAULT 0,
                password_hash TEXT, created_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS identities (provider TEXT NOT NULL, subject TEXT NOT NULL, login TEXT, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                PRIMARY KEY (provider, subject), UNIQUE (provider, user_id))""",
            """CREATE TABLE IF NOT EXISTS email_codes (email TEXT NOT NULL, purpose TEXT NOT NULL, code_hash TEXT NOT NULL, expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (email, purpose))""",
            """CREATE TABLE IF NOT EXISTS oauth_states (state TEXT PRIMARY KEY, verifier TEXT NOT NULL, link_user TEXT, expires_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, install_id TEXT NOT NULL,
                name TEXT NOT NULL, platform TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, last_seen_at INTEGER, phase TEXT, current_task TEXT,
                queued INTEGER NOT NULL DEFAULT 0, tasks_json TEXT NOT NULL DEFAULT '[]', UNIQUE (user_id, install_id))""",
            """CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL,
                device_id TEXT REFERENCES devices(id) ON DELETE CASCADE, access_hash TEXT NOT NULL UNIQUE, refresh_hash TEXT UNIQUE,
                access_expires_at INTEGER NOT NULL, refresh_expires_at INTEGER)""",
            """CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE, target_id TEXT NOT NULL, task_id TEXT NOT NULL, task_name TEXT NOT NULL,
                state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', lease_no INTEGER NOT NULL DEFAULT 0, lease_expires_at INTEGER,
                cancel_requested INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS jobs_by_device ON jobs (device_id, state, created_at)",
            "CREATE INDEX IF NOT EXISTS jobs_by_user ON jobs (user_id, created_at)",
        )
    }
}
