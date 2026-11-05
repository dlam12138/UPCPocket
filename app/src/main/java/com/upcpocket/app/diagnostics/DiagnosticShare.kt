package com.upcpocket.app.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Builds the only outbound diagnostic intent: an explicit ZIP content URI. */
object DiagnosticShare {
    const val MIME_TYPE = "application/zip"

    fun intent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("upc-pocket-diagnostics", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun chooser(context: Context, uri: Uri, title: String = "分享诊断报告"): Intent {
        val chooser = Intent.createChooser(intent(uri), title)
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return chooser
    }
}
