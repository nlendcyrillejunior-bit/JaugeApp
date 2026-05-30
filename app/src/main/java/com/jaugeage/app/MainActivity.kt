package com.jaugeage.app

import android.annotation.SuppressLint
import android.app.Activity
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

        // Passer en plein écran (cacher la barre de statut)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        supportActionBar?.hide()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        // Interface JS → Android pour télécharger les fichiers générés
        webView.addJavascriptInterface(DownloadInterface(this), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Laisser passer les URL internes (assets)
                if (url.startsWith("file://")) return false
                // Ouvrir les liens externes dans le navigateur
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                // Silencieux en prod
                return true
            }
        }

        // Injecter le pont JS pour le téléchargement de fichiers
        // (intercepts a.click() avec a.download)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }

        // Charger l'app depuis les assets
        webView.loadUrl("file:///android_asset/app.html")
    }

    // Gérer le bouton retour dans la WebView
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // ── Gestion des téléchargements (blob: URLs, data: URLs) ────────────────
    private fun handleDownload(url: String, contentDisposition: String, mimeType: String) {
        try {
            val fileName = extractFileName(contentDisposition, mimeType)
            when {
                url.startsWith("blob:") -> {
                    // Demander à la WebView de convertir le blob en base64 via JS
                    webView.evaluateJavascript("""
                        (function() {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', '$url', true);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    Android.downloadBase64(reader.result, '$fileName');
                                };
                                reader.readAsDataURL(xhr.response);
                            };
                            xhr.send();
                        })();
                    """.trimIndent(), null)
                }
                url.startsWith("data:") -> {
                    saveDataUrl(url, fileName)
                }
                else -> {
                    Toast.makeText(this, "Téléchargement: $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur téléchargement: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveDataUrl(dataUrl: String, fileName: String) {
        try {
            val base64 = dataUrl.substringAfter("base64,")
            val bytes = Base64.getDecoder().decode(base64)
            saveFile(bytes, fileName)
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveFile(bytes: ByteArray, fileName: String) {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: filesDir
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }

            // Notifier l'utilisateur et proposer d'ouvrir le fichier
            runOnUiThread {
                Toast.makeText(this, "✓ Fichier sauvegardé: $fileName", Toast.LENGTH_LONG).show()
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                    val mime = when {
                        fileName.endsWith(".pdf") -> "application/pdf"
                        fileName.endsWith(".csv") -> "text/csv"
                        else -> "*/*"
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Ouvrir avec"))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Erreur sauvegarde: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractFileName(contentDisposition: String, mimeType: String): String {
        val cd = contentDisposition.lowercase()
        val ext = when {
            mimeType.contains("pdf") -> ".pdf"
            mimeType.contains("csv") || mimeType.contains("text") -> ".csv"
            else -> ".bin"
        }
        return if (cd.contains("filename=")) {
            cd.substringAfter("filename=").trim('"', '\'', ' ')
        } else {
            "jaugeage_${System.currentTimeMillis()}$ext"
        }
    }
}

// ── Interface JavaScript → Android ──────────────────────────────────────────
class DownloadInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun downloadBase64(dataUrl: String, fileName: String) {
        activity.saveDataUrl(dataUrl, fileName)
    }

    @JavascriptInterface
    fun saveCSV(content: String, fileName: String) {
        activity.saveFile(content.toByteArray(Charsets.UTF_8), fileName)
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
