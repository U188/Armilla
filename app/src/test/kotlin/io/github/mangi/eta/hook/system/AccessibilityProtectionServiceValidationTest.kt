package io.github.mangi.eta.hook.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityProtectionServiceValidationTest {
    private val expected = ComponentName(
        "io.github.mangi.eta",
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService",
    )

    private fun service() = ServiceInfo().apply {
        packageName = expected.packageName
        name = expected.className
        enabled = true
        exported = false
        permission = Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        applicationInfo = ApplicationInfo().apply { enabled = true }
    }

    @Test fun nonExportedManifestServiceIsAccepted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(
            expected,
            PackageManager.ComponentInfoFlags.of(0L),
        )
        assertFalse(info.exported)
        assertTrue(isAccessibilityProtectionServiceValid(info, expected))
    }

    @Test fun systemOnlyServiceDoesNotRequireExportToOtherApps() {
        assertTrue(isAccessibilityProtectionServiceValid(service(), expected))
    }

    @Test fun missingOrWrongBindingPermissionIsRejected() {
        assertFalse(isAccessibilityProtectionServiceValid(service().apply { permission = null }, expected))
        assertFalse(isAccessibilityProtectionServiceValid(service().apply { permission = "other.permission" }, expected))
    }

    @Test fun disabledApplicationOrServiceIsRejected() {
        assertFalse(isAccessibilityProtectionServiceValid(service().apply { enabled = false }, expected))
        assertFalse(isAccessibilityProtectionServiceValid(service().apply { applicationInfo.enabled = false }, expected))
    }

    @Test fun differentPackageOrClassIsRejected() {
        assertFalse(isAccessibilityProtectionServiceValid(service().apply { packageName = "other.app" }, expected))
        assertFalse(isAccessibilityProtectionServiceValid(service().apply { name = "other.Service" }, expected))
    }
}
