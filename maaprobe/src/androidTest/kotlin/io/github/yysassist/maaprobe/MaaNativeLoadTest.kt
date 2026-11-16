package io.github.yysassist.maaprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaaNativeLoadTest {
    @Test fun fixedNativeDistributionLoadsWithoutNetworkOrController() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (app in listOf(context.packageName, instrumentation.context.packageName))
            assertEquals(PackageManager.PERMISSION_DENIED, context.packageManager.checkPermission(Manifest.permission.INTERNET, app))
        val report = JSONObject().put("phase", "loading").put("internetPermission", false).put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
        val file = context.filesDir.resolve("maa-probe/load.json"); file.parentFile!!.mkdirs(); file.writeText(report.toString(2))
        val started = SystemClock.elapsedRealtimeNanos()
        val version = MaaNative.version()
        report.put("version", version).put("loadMs", (SystemClock.elapsedRealtimeNanos() - started) / 1e6).put("phase", "loaded")
        file.writeText(report.toString(2))
        assertTrue(version.contains("5.16.1"))
    }
}
