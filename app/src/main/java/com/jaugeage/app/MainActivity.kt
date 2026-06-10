package com.jaugeage.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        supportActionBar?.hide()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.addJavascriptInterface(DownloadInterface(this), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file://")) return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage) = true
        }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }
        webView.loadUrl("file:///android_asset/app.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun handleDownload(url: String, contentDisposition: String, mimeType: String) {
        val fileName = extractFileName(contentDisposition, mimeType)
        when {
            url.startsWith("blob:") -> {
                webView.evaluateJavascript("""
                    (function(){var xhr=new XMLHttpRequest();xhr.open('GET','$url',true);
                    xhr.responseType='blob';xhr.onload=function(){var r=new FileReader();
                    r.onloadend=function(){Android.downloadBase64(r.result,'$fileName');};
                    r.readAsDataURL(xhr.response);};xhr.send();})();
                """.trimIndent(), null)
            }
            url.startsWith("data:") -> saveDataUrl(url, fileName)
        }
    }

    fun saveDataUrl(dataUrl: String, fileName: String) {
        try {
            val bytes = Base64.getDecoder().decode(dataUrl.substringAfter("base64,"))
            saveFile(bytes, fileName)
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    fun saveFile(bytes: ByteArray, fileName: String) {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            runOnUiThread {
                Toast.makeText(this, "✓ Sauvegardé: $fileName", Toast.LENGTH_LONG).show()
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                    val mime = if (fileName.endsWith(".pdf")) "application/pdf" else "text/csv"
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Ouvrir avec"
                    ))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun extractFileName(cd: String, mime: String): String {
        val ext = if (mime.contains("pdf")) ".pdf" else ".csv"
        return if (cd.lowercase().contains("filename="))
            cd.lowercase().substringAfter("filename=").trim('"','\'', ' ')
        else "jaugeage_${System.currentTimeMillis()}$ext"
    }
}

class DownloadInterface(private val a: MainActivity) {
    @JavascriptInterface fun downloadBase64(dataUrl: String, fileName: String) = a.saveDataUrl(dataUrl, fileName)
    @JavascriptInterface fun saveCSV(content: String, fileName: String) = a.saveFile(content.toByteArray(), fileName)
    @JavascriptInterface fun showToast(msg: String) = a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show() }
}
