package com.kmibrahim.deenorav3

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Deenora native call screen — WhatsApp-style SINGLE UI.
 * Design: Deenora_UI_Prototype (navy/blue premium) — web + app ektai design.
 * Ek activity te 2 ta state: INCOMING (ringing) -> CONNECTED (kotha cholche).
 * Programmatic UI (kono XML layout nei).
 *
 * Flow:
 * - Answer: MainActivity ke background audio-engine hishebe chalu kore
 *   (native_ui=true), CallActivity CONNECTED UI dekhay — kono web call UI ashe na.
 * - Decline/End: MainActivity r maddhome JS signaling (admin ke janano hoy),
 *   tarpor CallActivity finish.
 * - Notification tap / notification Answer button -> CallActivity tei ashe.
 */
class CallActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CALL_DISMISSED = "com.kmibrahim.deenorav3.CALL_DISMISSED"
        private const val INCOMING_TIMEOUT_MS = 45_000L
    }

    private enum class UiState { INCOMING, CONNECTED }

    private val handler = Handler(Looper.getMainLooper())
    private var uiState = UiState.INCOMING

    private var callerName = ""
    private var callerPhone = ""
    private var callerSubtitle = ""
    private var callId = ""

    private var callStartTime = 0L
    private var isMuted = false
    private var isSpeakerOn = true

    private lateinit var root: LinearLayout
    private var timerText: TextView? = null
    private var muteWrap: FrameLayout? = null
    private var speakerWrap: FrameLayout? = null
    private var muteIcon: ImageView? = null
    private var speakerIcon: ImageView? = null
    private var muteLabelTv: TextView? = null

    private val incomingTimeout = Runnable { finishCallUi() }

    private val timerTick = object : Runnable {
        override fun run() {
            val s = ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
            timerText?.text = String.format("%02d:%02d", s / 60, s % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            finishCallUi()
        }
    }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        readExtras(intent)

        val filter = IntentFilter(ACTION_CALL_DISMISSED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, filter)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = navyGradient()
            val p = dp(28)
            setPadding(p, dp(72), p, dp(40))
        }
        setContentView(root)

        // Notification er Answer button theke sorasori ele
        if (intent.getStringExtra("call_action") == "answer") {
            onAnswerClicked()
        } else {
            showIncomingUi()
            handler.postDelayed(incomingTimeout, INCOMING_TIMEOUT_MS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
        if (intent.getStringExtra("call_action") == "answer" && uiState == UiState.INCOMING) {
            onAnswerClicked()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(incomingTimeout)
        handler.removeCallbacks(timerTick)
        try {
            unregisterReceiver(dismissReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------------- extras / flags ----------------

    private fun readExtras(intent: Intent) {
        callerName = intent.getStringExtra("caller_name")
            ?: intent.getStringExtra("CALLER_NAME")
                    ?: "মাদরাসা এডমিন"
        callerPhone = intent.getStringExtra("caller_phone")
            ?: intent.getStringExtra("CALLER_PHONE")
                    ?: ""
        callerSubtitle = intent.getStringExtra("caller_subtitle") ?: ""
        val id = intent.getStringExtra("call_id") ?: intent.getStringExtra("CALL_ID")
        if (!id.isNullOrBlank()) callId = id
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- actions ----------------

    private fun onAnswerClicked() {
        if (uiState == UiState.CONNECTED) return
        uiState = UiState.CONNECTED
        handler.removeCallbacks(incomingTimeout)

        // Ringtone bondho, ongoing-call service chalu
        CallService.stop(this)
        OngoingCallService.start(this)
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            isSpeakerOn = true
        } catch (_: Exception) {
        }

        // WebView audio-engine background e chalu hobe — kono web UI dekhabe na
        signalToWeb("answer")

        showConnectedUi()
        callStartTime = System.currentTimeMillis()
        handler.post(timerTick)
    }

    private fun onDeclineClicked() {
        endCallFromNative("decline")
    }

    private fun onEndClicked() {
        endCallFromNative("end")
    }

    /**
     * Guardian katle admin keo janate hobe — tai MainActivity r maddhome
     * JS signaling (rejectCall/endCall -> Supabase broadcast) pathano hoy.
     */
    private fun endCallFromNative(action: String) {
        signalToWeb(action)
        try {
            CallService.stop(this)
            OngoingCallService.stop(this)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        finishCallUi()
    }

    /** MainActivity ke background engine hishebe signal pathano (UI dekhabe na). */
    private fun signalToWeb(action: String) {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("type", "incoming_call")
                putExtra("call_action", action)
                putExtra("native_ui", true)
                putExtra("call_id", callId)
                putExtra("caller_name", callerName)
                putExtra("CALLER_NAME", callerName)
                putExtra("CALL_ID", callId)
            }
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("native_ui", true)
                putExtra("audio_action", "mute_toggle")
                putExtra("muted", isMuted)
                putExtra("call_id", callId)
            }
            startActivity(i)
        } catch (_: Exception) {
        }
        refreshToggleIcons()
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = isSpeakerOn
        } catch (_: Exception) {
        }
        refreshToggleIcons()
    }

    private fun finishCallUi() {
        handler.removeCallbacks(incomingTimeout)
        handler.removeCallbacks(timerTick)
        try {
            finish()
        } catch (_: Exception) {
        }
    }

    // ---------------- INCOMING UI (prototype: Parent Incoming) ----------------

    private fun showIncomingUi() {
        root.removeAllViews()
        uiState = UiState.INCOMING

        root.addView(smallLabel("Incoming Call").apply {
            setPadding(0, 0, 0, dp(40))
        })

        // Institution icon tile + pulse ring
        val tileFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val pulse = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER)
            background = circleDrawable("#2E60A5FA")
        }
        val tile = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER)
            background = roundedRect("#0DFFFFFF", 28f, "#1AFFFFFF")
        }
        val schoolIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            setImageResource(R.drawable.ic_school)
            setColorFilter(Color.parseColor("#60A5FA"))
        }
        tile.addView(schoolIcon)
        tileFrame.addView(pulse)
        tileFrame.addView(tile)
        root.addView(tileFrame)
        startPulse(pulse)

        // Institution name
        root.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(32) }
            text = callerName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        // Phone
        if (callerPhone.isNotBlank()) {
            root.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                text = callerPhone
                setTextColor(Color.parseColor("#60A5FA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
            })
        }

        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        // Decline | Accept
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(callButtonColumn("Decline", "#EF4444", R.drawable.ic_call, 135f) { onDeclineClicked() })
        row.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(1))
        })
        row.addView(callButtonColumn("Accept", "#16A34A", R.drawable.ic_call, 0f) { onAnswerClicked() })
        root.addView(row)
    }

    private fun callButtonColumn(
        label: String,
        bgColor: String,
        iconRes: Int,
        iconRotation: Float,
        onClick: () -> Unit
    ): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val btn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = circleDrawable(bgColor)
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            rotation = iconRotation
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            text = label
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        col.addView(btn)
        col.addView(tv)
        return col
    }

    // ---------------- CONNECTED UI (prototype: Connected Call) ----------------

    private fun showConnectedUi() {
        root.removeAllViews()

        val initial = callerName.trim().firstOrNull()?.uppercase() ?: "D"
        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
            background = circleGradientDrawable()
            text = initial
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
        }
        root.addView(avatar)

        root.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            text = callerName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        if (callerSubtitle.isNotBlank()) {
            root.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                text = callerSubtitle
                setTextColor(Color.parseColor("#99FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            })
        }

        // Timer pill
        timerText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
            background = roundedRect("#1AFFFFFF", 20f, "#1AFFFFFF")
            setPadding(dp(18), dp(8), dp(18), dp(8))
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(timerText)

        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        // Mute | Keypad | Speaker
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        muteWrap = null
        speakerWrap = null
        val (mw, mi, ml) = toggleItem(R.drawable.ic_mic, "Mute") { toggleMute() }
        muteWrap = mw; muteIcon = mi; muteLabelTv = ml
        grid.addView(wrapColumn(mw, ml))
        grid.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(1))
        })
        val (kw, _, kl) = toggleItem(R.drawable.ic_dialpad, "Keypad") {
            Toast.makeText(this, "শীঘ্রই আসছে", Toast.LENGTH_SHORT).show()
        }
        grid.addView(wrapColumn(kw, kl))
        grid.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(1))
        })
        val (sw, si, sl) = toggleItem(R.drawable.ic_volume_up, "Speaker") { toggleSpeaker() }
        speakerWrap = sw; speakerIcon = si
        grid.addView(wrapColumn(sw, sl))
        root.addView(grid)
        refreshToggleIcons()

        // End button
        val endBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(36)
            }
            background = circleDrawable("#EF4444")
            setImageResource(R.drawable.ic_call)
            setColorFilter(Color.WHITE)
            setPadding(dp(22), dp(22), dp(22), dp(22))
            rotation = 135f
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onEndClicked() }
        }
        root.addView(endBtn)
    }

    /**
     * Ekta toggle item: [56dp circle wrap + vector icon] + label.
     * Return: Triple(wrap, iconView, labelView).
     */
    private fun toggleItem(
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ): Triple<FrameLayout, ImageView, TextView> {
        val wrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = circleDrawable("#33FFFFFF")
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
        }
        wrap.addView(icon)
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        return Triple(wrap, icon, tv)
    }

    private fun wrapColumn(wrap: FrameLayout, label: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(wrap)
            label.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(label)
        }
    }

    /** Prototype style: active (on) hole shada circle + dark icon. */
    private fun refreshToggleIcons() {
        muteWrap?.background = circleDrawable(if (isMuted) "#FFFFFF" else "#33FFFFFF")
        muteIcon?.setColorFilter(Color.parseColor(if (isMuted) "#10233F" else "#FFFFFF"))
        muteLabelTv?.text = if (isMuted) "Unmute" else "Mute"
        speakerWrap?.background = circleDrawable(if (isSpeakerOn) "#FFFFFF" else "#33FFFFFF")
        speakerIcon?.setColorFilter(Color.parseColor(if (isSpeakerOn) "#10233F" else "#FFFFFF"))
    }

    // ---------------- helpers ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun navyGradient(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#071B36"),
                Color.parseColor("#0A2347"),
                Color.parseColor("#071B36")
            )
        )
    }

    private fun circleDrawable(color: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
    }

    private fun circleGradientDrawable(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#1D4ED8"))
        ).apply { shape = GradientDrawable.OVAL }
    }

    private fun roundedRect(color: String, radiusDp: Float, strokeColor: String?): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(Color.parseColor(color))
            if (strokeColor != null) setStroke(dp(1), Color.parseColor(strokeColor))
        }
    }

    private fun smallLabel(text: String): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            this.text = text.uppercase()
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLetterSpacing(0.18f)
            gravity = Gravity.CENTER
        }
    }

    private fun startPulse(view: View) {
        val interp = AccelerateDecelerateInterpolator()
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.3f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = interp
            start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.3f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = interp
            start()
        }
        ObjectAnimator.ofFloat(view, "alpha", 0.5f, 0f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }
}
