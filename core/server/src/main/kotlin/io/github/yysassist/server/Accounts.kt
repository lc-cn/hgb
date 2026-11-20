package io.github.yysassist.server

import io.github.yysassist.protocol.MergeSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What the other account holds, so the user can see what a merge brings over. */
internal fun Database.mergeSummary(userId: String) = MergeSummary(
    email = single("SELECT email FROM users WHERE id = ? AND email_verified = 1", userId) { it.getString(1) },
    githubLogin = single("SELECT login FROM identities WHERE provider = 'github' AND user_id = ?", userId) { it.getString(1) },
    devices = count("SELECT COUNT(*) FROM devices WHERE user_id = ? AND revoked = 0", userId),
    gameAccounts = count("SELECT COUNT(*) FROM game_accounts WHERE user_id = ?", userId),
    plans = count("SELECT COUNT(*) FROM plans WHERE user_id = ?", userId),
)
private fun Database.count(sql: String, vararg args: Any?) = single(sql, *args) { it.getInt(1) } ?: 0

private val ids = ListSerializer(String.serializer())
private val idJson = Json { ignoreUnknownKeys = true }

/**
 * Moves everything of [source] into [target] and deletes [source]; run inside one transaction. Where both hold the
 * same thing, [target]'s wins:
 * - sign-in methods: the ones [target] lacks come over (its email only when it has none, with that email's password);
 * - devices: the same phone registered in both stays once, as [target]'s, and what pointed at the other copy follows;
 * - game accounts: the same game with the same name or uid is one account; [target]'s settings for it are kept;
 * - plans, jobs, notices, deliveries, channels and the devices' sessions come over (one email channel at most); its
 *   browsers are signed out;
 * - event rules, time zone and task bundles: [target]'s, plus what only [source] had.
 * Returns [source]'s device ids, whose sockets must be closed so they reconnect as [target].
 */
internal fun Database.mergeUser(source: String, target: String): List<String> {
    require(source != target)
    // Sign-in methods.
    update("DELETE FROM identities WHERE user_id = ? AND provider IN (SELECT provider FROM identities WHERE user_id = ?)", source, target)
    update("UPDATE identities SET user_id = ? WHERE user_id = ?", target, source)
    val targetHasEmail = single("SELECT 1 FROM users WHERE id = ? AND email IS NOT NULL AND email_verified = 1", target) { true } == true
    val sourceEmail = single("SELECT email, password_hash FROM users WHERE id = ? AND email IS NOT NULL AND email_verified = 1", source) { it.getString(1) to it.getString(2) }
    update("UPDATE users SET email = NULL WHERE id = ?", source)
    if (!targetHasEmail && sourceEmail != null) {
        update("UPDATE users SET email = ?, email_verified = 1, password_hash = COALESCE(password_hash, ?) WHERE id = ?", sourceEmail.first, sourceEmail.second, target)
    }

    // Devices: the same install in both accounts is one device.
    val sourceDevices = query("SELECT id, install_id FROM devices WHERE user_id = ?", source) { it.getString(1) to it.getString(2) }
    val sameDevice = sourceDevices.mapNotNull { (id, install) ->
        single("SELECT id FROM devices WHERE user_id = ? AND install_id = ?", target, install) { id to it.getString(1) } }.toMap()
    // Game accounts: the same game with the same name, or else the same uid, is one account.
    val sourceAccounts = query("SELECT id, game_id, name, uid FROM game_accounts WHERE user_id = ?", source) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
    val sameAccount = sourceAccounts.mapNotNull { (id, game, name, uid) ->
        (single("SELECT id FROM game_accounts WHERE user_id = ? AND game_id = ? AND name = ?", target, game, name) { it.getString(1) }
            ?: uid?.let { single("SELECT id FROM game_accounts WHERE user_id = ? AND game_id = ? AND uid = ?", target, game, it) { row -> row.getString(1) } })
            ?.let { id to it } }.toMap()

    for ((old, new) in sameDevice) {
        // The copy in use wins its state: the target's may be revoked or long unused while the source's is the live one.
        update("""UPDATE devices SET (name, platform, revoked, last_seen_at, phase, current_task, queued, tasks_json, catalog_version, catalog_digest, apps_json, concurrency) =
            (SELECT name, platform, revoked, last_seen_at, phase, current_task, queued, tasks_json, catalog_version, catalog_digest, apps_json, concurrency FROM devices WHERE id = ?)
            WHERE id = ? AND (revoked = 1 OR COALESCE(last_seen_at, 0) < (SELECT COALESCE(last_seen_at, 0) FROM devices WHERE id = ? AND revoked = 0))""", old, new, old)
        update("UPDATE jobs SET device_id = ? WHERE device_id = ?", new, old)
        update("UPDATE sessions SET device_id = ? WHERE device_id = ?", new, old)
        update("UPDATE notices SET device_id = ? WHERE device_id = ?", new, old)
        // Which game account each app is signed in to: the target's copy already says, unless it never did.
        update("INSERT OR IGNORE INTO app_accounts (device_id, app_key, user_id, account_id, default_account_id) SELECT ?, app_key, ?, account_id, default_account_id FROM app_accounts WHERE device_id = ?", new, target, old)
    }
    if (sameDevice.isNotEmpty()) {
        fun remap(json: String) = idJson.encodeToString(ids, idJson.decodeFromString(ids, json).map { sameDevice[it] ?: it }.distinct())
        query("SELECT id, device_ids_json FROM plans WHERE user_id = ?", source) { it.getString(1) to it.getString(2) }
            .forEach { (id, json) -> update("UPDATE plans SET device_ids_json = ? WHERE id = ?", remap(json), id) }
        query("SELECT id, allowed_devices_json FROM jobs WHERE user_id = ?", source) { it.getString(1) to it.getString(2) }
            .forEach { (id, json) -> update("UPDATE jobs SET allowed_devices_json = ? WHERE id = ?", remap(json), id) }
    }
    for ((old, new) in sameAccount) {
        for (table in listOf("plans", "jobs", "notices")) update("UPDATE $table SET account_id = ? WHERE account_id = ?", new, old)
        update("UPDATE app_accounts SET account_id = ? WHERE account_id = ?", new, old)
        update("UPDATE app_accounts SET default_account_id = ? WHERE default_account_id = ?", new, old)
        // Settings only the source had come along; where both have one, the target's stays.
        update("INSERT OR IGNORE INTO task_configs (user_id, account_id, target_id, task_id, values_json, revision, updated_at) SELECT ?, ?, target_id, task_id, values_json, revision, updated_at FROM task_configs WHERE account_id = ?", target, new, old)
        update("DELETE FROM game_accounts WHERE id = ?", old)
    }
    sameDevice.keys.forEach { update("DELETE FROM devices WHERE id = ?", it) }

    // A browser still signed in to the source is signed out rather than handed this account; devices stay signed in.
    update("DELETE FROM sessions WHERE user_id = ? AND kind = 'WEB'", source)
    update("DELETE FROM oauth_states WHERE link_user = ?", source)
    // Everything else changes hands as it is.
    for (table in listOf("devices", "game_accounts", "task_configs", "plans", "jobs", "app_accounts", "deliveries", "sessions"))
        update("UPDATE $table SET user_id = ? WHERE user_id = ?", target, source)
    // One email channel per account: the target's, if it has one.
    if (single("SELECT 1 FROM channels WHERE user_id = ? AND kind = 'EMAIL'", target) { true } == true)
        update("DELETE FROM channels WHERE user_id = ? AND kind = 'EMAIL'", source)
    update("UPDATE channels SET user_id = ? WHERE user_id = ?", target, source)
    // Where both have one with the same key, the target's stays and the source's goes with the source.
    update("UPDATE OR IGNORE notices SET user_id = ? WHERE user_id = ?", target, source)
    update("INSERT OR IGNORE INTO event_rules (user_id, rule_id, enabled, threshold, results_json, enabled_at) SELECT ?, rule_id, enabled, threshold, results_json, enabled_at FROM event_rules WHERE user_id = ?", target, source)
    update("INSERT OR IGNORE INTO catalogs (user_id, digest, version, bundle_json, uploaded_at) SELECT ?, digest, version, bundle_json, uploaded_at FROM catalogs WHERE user_id = ?", target, source)
    update("DELETE FROM users WHERE id = ?", source)
    return sourceDevices.map { it.first }
}
