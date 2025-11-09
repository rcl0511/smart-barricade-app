package com.example.myapplication.ui.util

import android.content.Context
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AlertDialog
import android.view.View

fun showSnack(view: View, msg: String) {
    Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show()
}

fun showRetryDialog(ctx: Context, title: String, message: String, onRetry: () -> Unit) {
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("재시도") { _, _ -> onRetry() }
        .setNegativeButton("취소", null)
        .show()
}
