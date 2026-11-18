package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
  private var webViewRef: WebView? = null
  private var pendingPermissionRequest: PermissionRequest? = null
  private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null

  private val audioPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
      val micGranted = results[Manifest.permission.RECORD_AUDIO] == true || hasRecordAudioPermission()
      val req = pendingPermissionRequest
      pendingPermissionRequest = null
      if (req != null) {
        if (micGranted) {
          runOnUiThread { req.grant(req.resources) }
        } else {
          runOnUiThread { req.deny() }
        }
      }
      if (micGranted) {
        configureBluetoothAudioRouteIfPresent()
      }
    }

  private val fileChooserLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val callback = pendingFilePathCallback
      pendingFilePathCallback = null
      if (callback == null) return@registerForActivityResult

      val data = result.data
      val uris: Array<Uri>? =
        if (result.resultCode == RESULT_OK && data != null) {
          val clip = data.clipData
          if (clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { idx -> clip.getItemAt(idx).uri }
          } else {
            data.data?.let { arrayOf(it) }
          }
        } else {
          null
        }
      callback.onReceiveValue(uris)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#0B0E14")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#0B0E14")),
    )

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val wv = webViewRef
          if (wv != null && wv.canGoBack()) {
            wv.goBack()
          } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
          }
        }
      },
    )

    requestInitialAudioPermissionsIfNeeded()

    setContent {
      VoxForgeAndroidHost(
        modifier =
          Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF0B0E14))
            .safeDrawingPadding(),
      )
    }
  }

  override fun onDestroy() {
    pendingPermissionRequest?.deny()
    pendingPermissionRequest = null
    pendingFilePathCallback?.onReceiveValue(null)
    pendingFilePathCallback = null
    webViewRef?.destroy()
    webViewRef = null
    super.onDestroy()
  }

  private fun hasRecordAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  private fun requiredAudioPermissions(): Array<String> {
    val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      list.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    return list.toTypedArray()
  }

  private fun requestInitialAudioPermissionsIfNeeded() {
    val missing =
      requiredAudioPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
      }
    if (missing.isNotEmpty()) {
      audioPermissionLauncher.launch(missing.toTypedArray())
    } else {
      configureBluetoothAudioRouteIfPresent()
    }
  }

  private fun configureBluetoothAudioRouteIfPresent() {
    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val btCommDevice =
          audioManager.availableCommunicationDevices.firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
              dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET
          }
        if (btCommDevice != null) {
          audioManager.setCommunicationDevice(btCommDevice)
        }
      }
    } catch (_: SecurityException) {
      // Missing BLUETOOTH_CONNECT permission; continue gracefully using default mic route
    } catch (_: Throwable) {
      // Ignore device routing errors on unsupported hardware
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Composable
  private fun VoxForgeAndroidHost(modifier: Modifier = Modifier) {
    AndroidView(
      modifier = modifier,
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          setBackgroundColor(Color.parseColor("#0B0E14"))
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
          }

          addJavascriptInterface(VoxForgeBridge(ctx), "VoxForgeNative")

          webViewClient =
            object : WebViewClient() {
              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
              ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != APP_ASSETS_HOST) return null
                val rawPath = (url.path ?: "/").trimStart('/')
                val assetRel = if (rawPath.isEmpty()) "index.html" else rawPath
                return serveBundledAsset(ctx, assetRel)
              }
            }

          webChromeClient =
            object : WebChromeClient() {
              override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val wantsAudio =
                  request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio) {
                  request.grant(request.resources)
                  return
                }
                if (hasRecordAudioPermission()) {
                  configureBluetoothAudioRouteIfPresent()
                  runOnUiThread { request.grant(request.resources) }
                } else {
                  pendingPermissionRequest?.deny()
                  pendingPermissionRequest = request
                  audioPermissionLauncher.launch(requiredAudioPermissions())
                }
              }

              override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
              ): Boolean {
                pendingFilePathCallback?.onReceiveValue(null)
                pendingFilePathCallback = filePathCallback
                val intent =
                  Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                      Intent.EXTRA_MIME_TYPES,
                      arrayOf("audio/*", "video/*"),
                    )
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                  }
                return try {
                  fileChooserLauncher.launch(intent)
                  true
                } catch (_: Exception) {
                  pendingFilePathCallback = null
                  false
                }
              }
            }

          webViewRef = this
          loadUrl("https://$APP_ASSETS_HOST/index.html")
        }
      },
    )
  }

  private fun serveBundledAsset(ctx: Context, assetPath: String): WebResourceResponse {
    val sanitized = assetPath.replace("..", "").trimStart('/')
    val candidate = "www/$sanitized"
    return try {
      val stream = ctx.assets.open(candidate)
      val mime = guessMimeType(sanitized)
      val headers = mapOf("Access-Control-Allow-Origin" to "https://$APP_ASSETS_HOST")
      WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream)
    } catch (_: Exception) {
      try {
        val fallback = ctx.assets.open("www/index.html")
        WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(), fallback)
      } catch (_: Exception) {
        WebResourceResponse(
          "text/plain",
          "UTF-8",
          404,
          "Not Found",
          emptyMap(),
          ByteArrayInputStream(ByteArray(0)),
        )
      }
    }
  }

  private fun guessMimeType(path: String): String {
    val lower = path.lowercase(Locale.US)
    return when {
      lower.endsWith(".html") -> "text/html"
      lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
      lower.endsWith(".css") -> "text/css"
      lower.endsWith(".json") -> "application/json"
      lower.endsWith(".ttf") -> "font/ttf"
      lower.endsWith(".woff2") -> "font/woff2"
      lower.endsWith(".png") -> "image/png"
      lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
      lower.endsWith(".svg") -> "image/svg+xml"
      lower.endsWith(".wav") -> "audio/wav"
      else -> "application/octet-stream"
    }
  }

  inner class VoxForgeBridge(private val ctx: Context) {
    @JavascriptInterface
    fun saveBase64File(base64Data: String, fileName: String, mimeType: String): Boolean {
      return try {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values =
            ContentValues().apply {
              put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
              put(MediaStore.Downloads.MIME_TYPE, mimeType)
              put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/VoxForge",
              )
            }
          val resolver = ctx.contentResolver
          val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          if (uri != null) {
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
            runOnUiThread {
              Toast.makeText(ctx, "Saved to Downloads/VoxForge/$cleanName", Toast.LENGTH_LONG).show()
            }
            return true
          }
        }

        val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, cleanName)
        FileOutputStream(outFile).use { it.write(bytes) }
        val shareUri =
          FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
        val shareIntent =
          Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
        runOnUiThread {
          ctx.startActivity(Intent.createChooser(shareIntent, "Save $cleanName"))
        }
        true
      } catch (e: Exception) {
        runOnUiThread {
          Toast.makeText(ctx, "Failed to save WAV: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        false
      }
    }
  }

  companion object {
    private const val APP_ASSETS_HOST = "appassets.androidplatform.net"
  }
}
