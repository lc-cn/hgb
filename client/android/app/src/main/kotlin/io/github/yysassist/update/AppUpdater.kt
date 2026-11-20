package io.github.yysassist.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.yysassist.core.update.AppRelease
import io.github.yysassist.core.update.AppReleases
import io.github.yysassist.core.update.AppUpdateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where updating the App stands, as 我的 → 本机 shows it. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    data class Downloading(val release: AppRelease, val bytes: Long) : AppUpdateState
    /** Downloaded and checked; installing hands it to the system, which asks the user. */
    data class Ready(val release: AppRelease) : AppUpdateState
    /** Being handed to the system installer, until its confirmation page shows. */
    data class Installing(val release: AppRelease) : AppUpdateState
    /** [retry] is false when trying again cannot help (the installed App is signed differently). */
    data class Failed(val message: String, val release: AppRelease? = null, val retry: Boolean = true) : AppUpdateState
}

/**
 * Checks the code repository for a newer App, downloads and checks its APK, and hands it to the system installer. The
 * system always asks the user before installing, and only accepts an APK signed like this one, so data is kept.
 */
class AppUpdater(private val context: Context, private val releases: AppReleases, private val current: String, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private val folder = File(context.cacheDir, "app-updates")
    private val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private var work: Job? = null
    private fun apk(release: AppRelease) = File(folder, "hgb-${release.version}.apk")

    /**
     * Checks at most every [CHECK_EVERY_MILLIS] on its own: the process also starts for the accessibility service and
     * notices, and api.github.com allows few anonymous requests per address.
     */
    fun checkIfDue() {
        if (System.currentTimeMillis() - preferences.getLong(CHECKED_AT, 0) >= CHECK_EVERY_MILLIS) check()
    }

    fun check() {
        if (work?.isActive == true) return
        work = scope.launch {
            mutableState.value = AppUpdateState.Checking
            mutableState.value = try {
                val found = releases.newer(current)
                preferences.edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply()
                // Older downloads are of no use once a newer one is out, or once this version is installed.
                withContext(Dispatchers.IO) { folder.listFiles()?.filter { found == null || it.name != apk(found).name }?.forEach { it.delete() } }
                when {
                    found == null -> AppUpdateState.UpToDate
                    apk(found).isFile -> AppUpdateState.Ready(found)
                    else -> AppUpdateState.Available(found)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppUpdateState.Failed(text(failure, "检查更新失败")) }
        }
    }

    fun download() {
        val release = when (val now = state.value) { is AppUpdateState.Available -> now.release; is AppUpdateState.Failed -> now.release; else -> null } ?: return
        if (work?.isActive == true) return
        work = scope.launch {
            mutableState.value = AppUpdateState.Downloading(release, 0)
            mutableState.value = try {
                releases.download(release, apk(release)) { bytes -> mutableState.value = AppUpdateState.Downloading(release, bytes) }
                AppUpdateState.Ready(release)
            } catch (cancelled: CancellationException) { mutableState.value = AppUpdateState.Available(release); throw cancelled }
            catch (failure: Exception) { AppUpdateState.Failed(text(failure, "下载失败"), release) }
        }
    }

    /**
     * Hands the checked APK to the system. The first time, Android asks the user to let 护肝宝 install apps; that page
     * opens instead, and installing again afterwards continues.
     */
    fun install() {
        val release = (state.value as? AppUpdateState.Ready)?.release ?: return
        if (work?.isActive == true) return
        if (!context.packageManager.canRequestPackageInstalls()) { openInstallPermission(); return }
        work = scope.launch {
            mutableState.value = AppUpdateState.Installing(release)
            val file = apk(release)
            if (!file.isFile) { mutableState.value = AppUpdateState.Available(release); return@launch }
            val installer = context.packageManager.packageInstaller
            var id = -1
            try {
                withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
                    id = installer.createSession(params)
                    installer.openSession(id).use { session ->
                        session.openWrite("hgb.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                        val result = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                        session.commit(result.intentSender)
                    }
                }
                // The system's confirmation page follows (InstallResultReceiver); until it answers, 安装 can be pressed again.
                mutableState.value = AppUpdateState.Ready(release)
            } catch (cancelled: CancellationException) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                mutableState.value = AppUpdateState.Ready(release); throw cancelled
            } catch (failure: Exception) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                mutableState.value = AppUpdateState.Failed("无法打开系统安装，请重试", release)
            }
        }
    }

    /** Android's "install unknown apps" switch for 护肝宝; some phones lack that page, so its app details open instead. */
    private fun openInstallPermission() {
        val pages = listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        if (pages.none { page -> try { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: ActivityNotFoundException) { false } })
            mutableState.value = AppUpdateState.Failed("请在系统设置里允许护肝宝“安装未知应用”，再回来点安装", (state.value as? AppUpdateState.Ready)?.release)
    }

    internal fun installFailed(status: Int, detail: String?) {
        val release = (state.value as? AppUpdateState.Ready)?.release
        mutableState.value = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                AppUpdateState.Failed("新版本和已装的护肝宝签名不同，不能直接覆盖安装", null, retry = false)
            PackageInstaller.STATUS_FAILURE_STORAGE -> AppUpdateState.Failed("手机空间不足，清理后再点重试", release)
            else -> AppUpdateState.Failed("安装没有完成（系统代码 $status${detail?.let { "：$it" } ?: ""}），请重试", release)
        }
        if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) release?.let { apk(it).delete() }
    }

    private fun text(failure: Exception, what: String) = when (failure) {
        is AppUpdateException -> failure.message ?: what
        is IOException -> "$what：连不上 GitHub，请检查网络后重试"
        else -> "$what，请重试"
    }

    private companion object {
        const val CHECKED_AT = "checkedAt"
        const val CHECK_EVERY_MILLIS = 12 * 60 * 60 * 1000L
    }
}

/** The system's answer to an install: show its confirmation page, or report why it did not install. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as? io.github.yysassist.AssistApplication)?.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // This process is replaced by the new version.
            // Cancelled on the system's page: the checked APK stays, and 安装 offers it again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> updater?.installFailed(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
