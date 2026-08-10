package com.opennovel.reader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plain in-app browser for a source, used to solve captchas, sign in, or check
 * why a source has stopped returning results.
 *
 * It deliberately shares the system WebView cookie jar: a Cloudflare clearance
 * cookie earned here is exactly what the source's own requests need afterwards.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWebViewScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    // In-page history first: backing out of a login flow should not leave the screen.
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                }
            },
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        onClick = { menuOpen = false; webView?.reload() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = { menuOpen = false; onShare(currentUrl) },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in browser") },
                        onClick = { menuOpen = false; onOpenInBrowser(currentUrl) },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear cookies") },
                        onClick = {
                            menuOpen = false
                            clearCookiesFor(currentUrl)
                            webView?.reload()
                        },
                    )
                }
            },
        )

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                            loading = true
                            pageUrl?.let { currentUrl = it }
                            canGoBack = view.canGoBack()
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            loading = false
                            pageUrl?.let { currentUrl = it }
                            canGoBack = view.canGoBack()
                        }
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(url)
                    webView = this
                }
            },
        )
    }
}

/**
 * Expires this site's cookies only; a global wipe would sign the user out of
 * every other source too.
 */
private fun clearCookiesFor(url: String) {
    runCatching {
        val manager = CookieManager.getInstance()
        val host = android.net.Uri.parse(url).host ?: return@runCatching
        val cookies = manager.getCookie(url) ?: return@runCatching
        cookies.split(';').forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) manager.setCookie(url, "$name=; Max-Age=0; path=/; domain=$host")
        }
        manager.flush()
    }
}
