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
                password_hash TEXT, created_at INTEGER NOT NULL, time_zone TEXT NOT NULL DEFAULT 'Asia/Shanghai')""",
            """CREATE TABLE IF NOT EXISTS identities (provider TEXT NOT NULL, subject TEXT NOT NULL, login TEXT, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                PRIMARY KEY (provider, subject), UNIQUE (provider, user_id))""",
            """CREATE TABLE IF NOT EXISTS email_codes (email TEXT NOT NULL, purpose TEXT NOT NULL, code_hash TEXT NOT NULL, expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (email, purpose))""",
            """CREATE TABLE IF NOT EXISTS oauth_states (state TEXT PRIMARY KEY, verifier TEXT NOT NULL, link_user TEXT, expires_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, install_id TEXT NOT NULL,
                name TEXT NOT NULL, platform TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, last_seen_at INTEGER, phase TEXT, current_task TEXT,
                queued INTEGER NOT NULL DEFAULT 0, tasks_json TEXT NOT NULL DEFAULT '[]', catalog_version TEXT, catalog_digest TEXT,
                apps_json TEXT NOT NULL DEFAULT '[]', concurrency INTEGER NOT NULL DEFAULT 1, UNIQUE (user_id, install_id))""",
            // Task bundles devices run, kept per user so settings can be checked against the exact version a job will use.
            """CREATE TABLE IF NOT EXISTS catalogs (user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, digest TEXT NOT NULL,
                version TEXT NOT NULL, bundle_json TEXT NOT NULL, uploaded_at INTEGER NOT NULL, PRIMARY KEY (user_id, digest))""",
            // Game accounts own settings, plans and run records; they are not tied to a device.
            """CREATE TABLE IF NOT EXISTS game_accounts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                game_id TEXT NOT NULL, name TEXT NOT NULL, uid TEXT, nickname TEXT, created_at INTEGER NOT NULL, UNIQUE (user_id, game_id, name))""",
            "CREATE UNIQUE INDEX IF NOT EXISTS game_accounts_by_uid ON game_accounts (user_id, game_id, uid) WHERE uid IS NOT NULL",
            // The server is the one place task settings are kept, per game account; jobs carry a copy taken when they are created.
            """CREATE TABLE IF NOT EXISTS task_configs (user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                account_id TEXT NOT NULL REFERENCES game_accounts(id) ON DELETE CASCADE, target_id TEXT NOT NULL, task_id TEXT NOT NULL,
                values_json TEXT NOT NULL, revision INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (account_id, target_id, task_id))""",
            """CREATE TABLE IF NOT EXISTS plans (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                account_id TEXT NOT NULL REFERENCES game_accounts(id) ON DELETE CASCADE, name TEXT NOT NULL, days_mask INTEGER NOT NULL,
                minute_of_day INTEGER NOT NULL, tasks_json TEXT NOT NULL, device_ids_json TEXT NOT NULL, latest_start_minutes INTEGER NOT NULL,
                enabled INTEGER NOT NULL, revision INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                fired_up_to INTEGER NOT NULL)""",
            // Which account an app is signed in to, as the user says until the app can read it, and its default account.
            """CREATE TABLE IF NOT EXISTS app_accounts (device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE, app_key TEXT NOT NULL,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, account_id TEXT REFERENCES game_accounts(id) ON DELETE SET NULL,
                default_account_id TEXT REFERENCES game_accounts(id) ON DELETE SET NULL, PRIMARY KEY (device_id, app_key))""",
            """CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL,
                device_id TEXT REFERENCES devices(id) ON DELETE CASCADE, access_hash TEXT NOT NULL UNIQUE, refresh_hash TEXT UNIQUE,
                access_expires_at INTEGER NOT NULL, refresh_expires_at INTEGER)""",
            // A job waits (no device) until the server finds an app for it; version and digest are set when it is assigned.
            """CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                device_id TEXT REFERENCES devices(id) ON DELETE CASCADE, app_key TEXT, target_id TEXT NOT NULL, task_id TEXT NOT NULL, task_name TEXT NOT NULL,
                state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', lease_no INTEGER NOT NULL DEFAULT 0, lease_expires_at INTEGER,
                cancel_requested INTEGER NOT NULL DEFAULT 0, pause_requested INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                version TEXT NOT NULL DEFAULT '', digest TEXT NOT NULL DEFAULT '', config_json TEXT NOT NULL,
                account_id TEXT REFERENCES game_accounts(id) ON DELETE SET NULL, account_name TEXT,
                plan_id TEXT REFERENCES plans(id) ON DELETE SET NULL, plan_name TEXT, allowed_devices_json TEXT NOT NULL DEFAULT '[]',
                latest_start_at INTEGER, sequence INTEGER NOT NULL DEFAULT 0)""",
            // A job's run log, sent by the device while it runs. key is the device's own record name, so resends are stored once.
            """CREATE TABLE IF NOT EXISTS job_events (job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, key TEXT NOT NULL, at INTEGER NOT NULL,
                kind TEXT NOT NULL, step TEXT NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL, elapsed_ms INTEGER NOT NULL, PRIMARY KEY (job_id, key))""",
            "CREATE INDEX IF NOT EXISTS jobs_by_device ON jobs (device_id, state, created_at)",
            "CREATE INDEX IF NOT EXISTS jobs_by_state ON jobs (state, created_at)",
            "CREATE INDEX IF NOT EXISTS jobs_by_user ON jobs (user_id, created_at)",
            "CREATE INDEX IF NOT EXISTS plans_by_user ON plans (user_id, account_id)",
            "CREATE INDEX IF NOT EXISTS jobs_by_plan ON jobs (plan_id, created_at)",
        )
    }
}
