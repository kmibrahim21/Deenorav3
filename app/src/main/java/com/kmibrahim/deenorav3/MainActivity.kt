package com.kmibrahim.deenorav3

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var singlePhotoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var multiPhotoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    companion object {
        private const val CHANNEL_ID = "deenora_downloads"
        private const val TAG = "DeenoraV3"
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) {
                runOnUiThread {
                    Toast.makeText(context, "ডাউনলোড সম্পন্ন হয়েছে। ফাইলটি 'Downloads' ফোল্ডারে দেখুন।", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // FCM push token sync
        FcmService.syncToken(this)
        KeepAliveService.start(this)

        setupLaunchers()
        setupWebView()

        webView.loadUrl(buildStartUrl(intent))

        setupBackButton()
        checkAndRequestPermissions()
        createNotificationChannel()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(onDownloadComplete, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = buildStartUrl(intent)
        if (url != "https://deenora.app") {
            webView.loadUrl(url)
        }
    }

    private fun buildStartUrl(intent: Intent?): String {
        val extras = intent?.extras ?: return "https://deenora.app"
        val callerName = extras.getString("caller_name")
        val callId = extras.getString("call_id")
        val type = extras.getString("type")

        if (!callerName.isNullOrBlank() || type == "incoming_call") {
            val params = StringBuilder("https://deenora.app?fcm_tap=1")
            if (!callId.isNullOrBlank()) params.append("&call_id=").append(Uri.encode(callId))
            if (!callerName.isNullOrBlank()) params.append("&caller_name=").append(Uri.encode(callerName))
            extras.getString("student_name")?.takeIf { it.isNotBlank() }?.let {
                params.append("&student_name=").append(Uri.encode(it))
            }
            extras.getString("institution_id")?.takeIf { it.isNotBlank() }?.let {
                params.append("&institution_id=").append(Uri.encode(it))
            }
            return params.toString()
        }
        return "https://deenora.app"
    }

    private fun setupLaunchers() {
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val results = result.data?.let { data ->
                    val clipData = data.clipData
                    if (clipData != null) {
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    } else {
                        data.data?.let { arrayOf(it) }
                    }
                }
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

        singlePhotoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            filePathCallback = null
        }

        multiPhotoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            filePathCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
            filePathCallback = null
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }
    }

    private fun setupWebView() {
        applySettings(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // Fix for Google Sign-in: Handling popups
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                applySettings(newWebView)
                newWebView.webChromeClient = this

                // Redirect popup navigation to the main WebView
                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString()
                        if (url != null) {
                            webView.loadUrl(url)
                            return true
                        }
                        return false
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes ?: arrayOf()
                val isMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val onlyMedia = acceptTypes.isNotEmpty() && acceptTypes.all { it.contains("image") || it.contains("video") }

                return try {
                    if (onlyMedia) {
                        val mediaType = when {
                            acceptTypes.any { it.contains("image") } && acceptTypes.any { it.contains("video") } -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            acceptTypes.any { it.contains("image") } -> ActivityResultContracts.PickVisualMedia.ImageOnly
                            else -> ActivityResultContracts.PickVisualMedia.VideoOnly
                        }
                        val request = PickVisualMediaRequest(mediaType)
                        if (isMultiple) multiPhotoPickerLauncher.launch(request) else singlePhotoPickerLauncher.launch(request)
                    } else {
                        val intent = fileChooserParams?.createIntent()
                        if (intent != null) filePickerLauncher.launch(intent) else throw Exception("Intent is null")
                    }
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                injectBlobHook(view)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                injectBlobHook(view)
                injectDownloadScripts(view)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                if (url.lowercase(Locale.ROOT).contains(".pdf") && !url.startsWith("blob:") && !url.startsWith("data:")) {
                    handleDownload(url, webView.settings.userAgentString, null, "application/pdf")
                    return true
                }
                return handleExternalUrls(url, view)
            }
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        // Native Google Sign-In bridge — phone er account picker khulbe (existing Gmail list soho)
        webView.addJavascriptInterface(
            GoogleSignInBridge(this) { idToken ->
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onNativeGoogleToken && window.onNativeGoogleToken(${if (idToken != null) "'$idToken'" else "null"})",
                        null
                    )
                }
            },
            "AndroidGoogleSignIn"
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun handleExternalUrls(url: String, view: WebView?): Boolean {
        if (url.contains("youtube.com") || url.contains("youtu.be") || url.startsWith("vnd.youtube:")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                return true
            } catch (e: Exception) {
                return false
            }
        }
        if (url.startsWith("intent://")) {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    if (info != null) {
                        startActivity(intent)
                    } else {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (fallbackUrl != null) {
                            view?.loadUrl(fallbackUrl)
                        }
                    }
                    return true
                }
            } catch (e: Exception) { }
            return true
        }
        if (url.contains("wa.me") || url.startsWith("whatsapp:") ||
            url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                return true
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun applySettings(v: WebView) {
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true) // Required for Google Sign-in popups
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Modern Chrome User-Agent to ensure Google allows Sign-in
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            webView.evaluateJavascript("if(typeof window.triggerDownload === 'function') window.triggerDownload('$url', '');", null)
            return
        }
        var effectiveMimeType = mimeType
        if (effectiveMimeType.isNullOrEmpty()) {
            effectiveMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, effectiveMimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(effectiveMimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (ex: Exception) { }
        }
    }

    private fun saveBase64ToFile(base64Data: String?, fileName: String?, mimeType: String?) {
        if (base64Data.isNullOrEmpty()) return
        try {
            val dataPart = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
            val bytes = Base64.decode(dataPart.trim(), Base64.DEFAULT)
            var cleanFileName = fileName?.ifEmpty { "deenora_file_" + System.currentTimeMillis() } ?: ("file_" + System.currentTimeMillis())
            cleanFileName = cleanFileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) { }
    }

    private fun injectBlobHook(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                if (window.deenoraBlobHooked) return;
                window.deenoraBlobHooked = true;
                window.deenoraBlobs = window.deenoraBlobs || {};
                var origCreate = URL.createObjectURL;
                URL.createObjectURL = function(obj) {
                    var url = origCreate(obj);
                    if (obj instanceof Blob) window.deenoraBlobs[url] = obj;
                    return url;
                };
            })();
        """.trimIndent(), null)
    }

    private fun injectDownloadScripts(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                if (window.deenoraScriptsLoaded) return;
                window.deenoraScriptsLoaded = true;
                window.triggerDownload = function(url, filename) {
                    if (!url) return;
                    if (url.startsWith('blob:')) {
                        var blob = (window.deenoraBlobs && window.deenoraBlobs[url]) || null;
                        if (blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AndroidInterface.downloadFile(reader.result, filename || 'document', blob.type || 'application/pdf');
                            };
                            reader.readAsDataURL(blob);
                        }
                    }
                };
            })();
        """.trimIndent(), null)
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun downloadFile(base64: String, name: String, mime: String) {
            saveBase64ToFile(base64, name, mime)
        }

        @JavascriptInterface
        fun getFcmToken(): String {
            return getSharedPreferences("deenora_push", Context.MODE_PRIVATE).getString("fcm_token", "") ?: ""
        }
    }
}
