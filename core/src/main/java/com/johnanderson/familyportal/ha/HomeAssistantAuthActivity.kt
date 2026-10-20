package com.johnanderson.familyportal.ha

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract

class HomeAssistantAuthActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authorizationUrl = intent.getStringExtra(EXTRA_AUTHORIZATION_URL)
        if (authorizationUrl.isNullOrBlank()) {
            finish()
            return
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleUri(request.url)

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleUri(Uri.parse(url))
            }
        }
        setContentView(webView)
        webView.loadUrl(authorizationUrl)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun handleUri(uri: Uri): Boolean {
        if (!HomeAssistantAuthManager.isAuthorizationCallback(uri)) return false
        setResult(Activity.RESULT_OK, Intent().setData(uri))
        finish()
        return true
    }

    /** Launches the login page and returns the authorization callback, or null if cancelled. */
    class Authorize : ActivityResultContract<Uri, Uri?>() {
        override fun createIntent(context: Context, input: Uri): Intent =
            Intent(context, HomeAssistantAuthActivity::class.java)
                .putExtra(EXTRA_AUTHORIZATION_URL, input.toString())

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }

    companion object {
        const val EXTRA_AUTHORIZATION_URL = "authorization_url"
    }
}
