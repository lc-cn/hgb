package io.github.yysassist.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yysassist.ui.cloudServerIssue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudServerAddressTest {
    @Test fun releaseBuildsOnlyAcceptHttps() {
        assertNull(cloudServerIssue("https://cloud.example.test", debug = false))
        assertNotNull(cloudServerIssue("http://cloud.example.test", debug = false))
        assertNotNull(cloudServerIssue("http://10.0.2.2:8787", debug = false))
    }
    @Test fun debugBuildsAllowOnlyTheLocalDevelopmentServerOverHttp() {
        assertNull(cloudServerIssue("http://10.0.2.2:8787", debug = true))
        assertNotNull(cloudServerIssue("http://192.168.1.2:8787", debug = true))
        assertNotNull(cloudServerIssue("不是地址", debug = true))
    }
}
