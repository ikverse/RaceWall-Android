package com.racewall.android.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.racewall.account.Token
import com.racewall.android.R
import com.racewall.android.account.LOGIN_URL
import kotlinx.coroutines.delay

/** The domains whose cookies may hold the login session. */
private val COOKIE_URLS = listOf("https://www.formula1.com", "https://formula1.com", "https://account.formula1.com")

/** Every subscription token in formula1.com's cookies, read through Android's cookie store. */
fun tokenInCookies(cookies: CookieManager = CookieManager.getInstance()): String? {
    for (url in COOKIE_URLS) {
        val header = cookies.getCookie(url) ?: continue
        for (pair in header.split(';')) {
            val value = pair.substringAfter('=', "").trim()
            Token.fromCookie(value)?.let { return it }
        }
    }
    return null
}

/** Clears formula1.com's sign-in from this app's web view. */
fun clearSignIn() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

/**
 * formula1.com's own sign-in page, full screen over RaceWall, as the desktop's separate sign-in
 * window. It checks the cookies once a second and closes once the subscription token is there.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInDialog(onToken: suspend (String) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            val token = tokenInCookies()
            if (token != null) {
                onToken(token)
                onClose()
                return@LaunchedEffect
            }
            delay(1000)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Rw.bg)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .drawBehind { drawLine(Rw.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Sign in with your F1 account", style = Rw.text(14f), modifier = Modifier.weight(1f))
                RwButton(onClick = onClose, label = "Close sign-in", horizontalPadding = 8f) { c -> Icon(R.drawable.ic_dismiss, c) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
