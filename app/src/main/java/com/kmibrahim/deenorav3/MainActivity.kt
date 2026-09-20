package com.kmibrahim.deenorav3

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
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

    // --- Call-bridge state -------------------------------------------------
    // Tracks whether deenora.app has actually finished loading. If a call
    // intent arrives before this is true, we hold onto it and dispatch it
    // once the page is ready — otherwise the JS call is fired into a blank
    // page and silently lost, which was the original bug.
    private var webViewReady = false
    private data class PendingCall(val action: String, val callerName: String, val callId: String)
    private var pendingCall: PendingCall? = null
    // -------------------------------------------------------------------

    companion object {
        private const val CHANNEL_ID = "deenora_downloads"
        private const val CALL_CHANNEL_ID = "voice_call_channel"
        private const val TAG = "DeenoraV3"
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) {
                runOnUiThread {
                    Toast.makeText(context, "ডাউনলোড সম্পন্ন হয়েছে। ফাইলটি 'Downloads' ফোল্ডারে দেখুন।", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupLockScreen()

        webView = WebView(this)
        setContentView(webView)

        setupLaunchers()
        setupWebView()

        // Parse any incoming-call intent BEFORE loadUrl — but do not try to
        // talk to the page yet, it doesn't exist. handleIntent() below just
        // queues it into pendingCall; onPageFinished() flushes it.
        handleIntent(intent)

        webView.loadUrl("https://deenora.app")

        setupBackButton()
        checkAndRequestPermissions()
        createNotificationChannels()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(onDownloadComplete, filter)
        }
    }

    private fun setupLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App was already running (foreground/background) — page is already
        // loaded, so this can be dispatched straight away.
        handleIntent(intent)
    }

    /**
     * Reads the call extras CallService puts on the launch Intent and either
     * dispatches them to the web app immediately (page already loaded) or
     * queues them for onPageFinished to flush.
     *
     * action extra values coming from CallService:
     *  - absent / "RINGING": user tapped the full-screen incoming-call notification
     *  - "ANSWER": user tapped the Answer action on the notification
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("INCOMING_CALL", false) != true) return

        val callerName = intent.getStringExtra("CALLER_NAME") ?: "Unknown Caller"
        val callId = intent.getStringExtra("CALL_ID") ?: ""
        val rawAction = intent.getStringExtra("ACTION") ?: "RINGING"
        val jsAction = if (rawAction == "ANSWER") "answer" else "ringing"

        val call = PendingCall(jsAction, callerName, callId)
        if (webViewReady) {
            dispatchCallToWeb(call)
        } else {
            pendingCall = call
        }
    }

    /**
     * Calls window.onNativeCallAction(action, callerName, callId) inside
     * deenora.app. Your web app needs to define this function — have it
     * show/hide the call UI and start/attach WebRTC based on `action`
     * ("ringing" | "answer").
     */
    private fun dispatchCallToWeb(call: PendingCall) {
        val js = """
            (function() {
                if (typeof window.onNativeCallAction === 'function') {
                    window.onNativeCallAction(
                        ${JSONObject.quote(call.action)},
                        ${JSONObject.quote(call.callerName)},
                        ${JSONObject.quote(call.callId)}
                    );
                } else {
                    console.warn('onNativeCallAction is not defined on window yet');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) { }
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
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
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

                webViewReady = true
                pendingCall?.let {
                    dispatchCallToWeb(it)
                    pendingCall = null
                }
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

        webView.addJavascriptInterface(WebAppInterface(this), "Android")

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
            } catch (e: Exception) {
                Log.e(TAG, "Intent error: ${e.message}")
            }
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
                if (url.contains("whatsapp")) Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
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
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
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
        if (effectiveMimeType == null && url.lowercase(Locale.ROOT).contains(".pdf")) effectiveMimeType = "application/pdf"

        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, effectiveMimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(effectiveMimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "ডাউনলোড শুরু হচ্ছে: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (ex: Exception) { }
        }
    }

    private fun saveBase64ToFile(base64Data: String?, fileName: String?, mimeType: String?) {
        if (base64Data.isNullOrEmpty()) return

        try {
            val dataPart = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
            val bytes = Base64.decode(dataPart.trim(), Base64.DEFAULT)

            val map = MimeTypeMap.getSingleton()
            val cleanMime = mimeType?.split(";")?.get(0)?.trim()?.lowercase() ?: "application/pdf"
            var extension = map.getExtensionFromMimeType(cleanMime)

            if (extension == null) {
                if (cleanMime.contains("pdf") || (fileName != null && fileName.lowercase(Locale.ROOT).contains(".pdf"))) extension = "pdf"
            }

            var cleanFileName = fileName?.ifEmpty { "deenora_file_" + System.currentTimeMillis() } ?: ("file_" + System.currentTimeMillis())
            cleanFileName = cleanFileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

            if (extension != null && !cleanFileName.lowercase(Locale.ROOT).endsWith(".$extension")) {
                cleanFileName += ".$extension"
            } else if (extension == null && !cleanFileName.contains(".")) {
                cleanFileName += ".pdf"
                extension = "pdf"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (extension == "pdf") "application/pdf" else cleanMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    runOnUiThread { Toast.makeText(this, "Downloads ফোল্ডারে সেভ হয়েছে: $cleanFileName", Toast.LENGTH_LONG).show() }
                }
            } else {
                val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!directory.exists()) directory.mkdirs()
                val file = File(directory, cleanFileName)
                FileOutputStream(file).use { it.write(bytes) }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                runOnUiThread { Toast.makeText(this, "Downloads ফোল্ডারে সেভ হয়েছে: $cleanFileName", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}")
            runOnUiThread { Toast.makeText(this, "সেভ করতে সমস্যা হয়েছে: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
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
                                Android.downloadFile(reader.result, filename || 'document', blob.type || 'application/pdf');
                            };
                            reader.readAsDataURL(blob);
                        } else {
                            fetch(url).then(r => r.blob()).then(b => {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    Android.downloadFile(reader.result, filename || 'document', b.type || 'application/pdf');
                                };
                                reader.readAsDataURL(b);
                            }).catch(e => {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', url, true);
                                xhr.responseType = 'blob';
                                xhr.onload = function() {
                                    if (this.status === 200) {
                                        var b = this.response;
                                        var reader = new FileReader();
                                        reader.onloadend = function() {
                                            Android.downloadFile(reader.result, filename || 'document', b.type || 'application/pdf');
                                        };
                                        reader.readAsDataURL(b);
                                    }
                                };
                                xhr.send();
                            });
                        }
                    } else if (url.startsWith('data:')) {
                        var p = url.split(',');
                        if (p.length > 1) {
                            var mime = p[0].split(':')[1].split(';')[0];
                            Android.downloadFile(p[1], filename || 'document', mime);
                        }
                    }
                };

                document.addEventListener('click', function(e) {
                    var a = e.target.closest('a');
                    if (a && a.href && (a.href.startsWith('data:') || a.href.startsWith('blob:'))) {
                        window.triggerDownload(a.href, a.getAttribute('download'));
                        e.preventDefault();
                    }
                }, true);

                var origOpen = window.open;
                window.open = function(url, name, specs) {
                    if (url && (url.startsWith('blob:') || url.startsWith('data:'))) {
                        window.triggerDownload(url, '');
                        return null;
                    }
                    return origOpen(url, name, specs);
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val downloadChannel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(downloadChannel)

            val callChannel = NotificationChannel(CALL_CHANNEL_ID, "Incoming Voice Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notification for incoming WebRTC voice calls"
                enableVibration(true)
                vibrationPattern = longArrayOf(1000, 500, 1000, 500, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC

                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, audioAttributes)
            }
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadFile(base64: String, name: String, mime: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "ডাউনলোড শুরু হচ্ছে...", Toast.LENGTH_SHORT).show()
            }
            saveBase64ToFile(base64, name, mime)
        }

        /**
         * Call this from deenora.app's JS once the user hangs up / declines
         * from the web UI, so the persistent call notification is cleared.
         */
        @JavascriptInterface
        fun stopCallService() {
            val intent = Intent(context, CallService::class.java).apply {
                action = "STOP_SERVICE"
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}