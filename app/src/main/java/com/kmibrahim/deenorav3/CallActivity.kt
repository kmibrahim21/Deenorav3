package com.kmibrahim.deenorav3

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CallActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        finish()
    }

    private var callerName: String = ""
    private var callId: String = ""
    private lateinit var nameText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock screen er upore full-screen dekhano nischit kora
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

        callerName = intent.getStringExtra("caller_name")
            ?: intent.getStringExtra("CALLER_NAME")
                    ?: "মাদরাসা এডমিন"
        callId = intent.getStringExtra("call_id")
            ?: intent.getStringExtra("CALL_ID")
                    ?: ""

        // Programmatic premium dark UI (kono XML layout file er upor nirvorota nei)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val pad = dpToPx(32)
            setPadding(pad, dpToPx(64), pad, dpToPx(48))
        }

        val statusText = TextView(this).apply {
            text = "ইনকামিং ভয়েস কল"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        nameText = TextView(this).apply {
            text = callerName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8), 0, 0)
        }
        rootLayout.addView(nameText)

        val avatarContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(130), dpToPx(130)).apply {
                topMargin = dpToPx(56)
                bottomMargin = dpToPx(40)
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(3), Color.parseColor("#334155"))
            }
        }
        val avatarIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(64))
            setImageResource(android.R.drawable.sym_call_incoming)
            setColorFilter(Color.WHITE)
        }
        avatarContainer.addView(avatarIcon)
        rootLayout.addView(avatarContainer)

        val spacer = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        rootLayout.addView(spacer)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val declineBtn = Button(this).apply {
            text = "কেটে দিন\nDecline"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(28).toFloat()
                setColor(Color.parseColor("#DC2626"))
            }
            val lp = LinearLayout.LayoutParams(0, dpToPx(58), 1.0f).apply {
                marginEnd = dpToPx(16)
            }
            layoutParams = lp
            setOnClickListener {
                val actIntent = Intent(this@CallActivity, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("caller_name", callerName)
                    putExtra("call_id", callId)
                    putExtra("CALLER_NAME", callerName)
                    putExtra("CALL_ID", callId)
                    putExtra("type", "incoming_call")
                    putExtra("call_action", "decline")
                }
                startActivity(actIntent)
                finish()
            }
        }
        buttonRow.addView(declineBtn)

        val answerBtn = Button(this).apply {
            text = "উত্তর দিন\nAnswer"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(28).toFloat()
                setColor(Color.parseColor("#16A34A"))
            }
            val lp = LinearLayout.LayoutParams(0, dpToPx(58), 1.0f).apply {
                marginStart = dpToPx(16)
            }
            layoutParams = lp
            setOnClickListener {
                val actIntent = Intent(this@CallActivity, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("caller_name", callerName)
                    putExtra("call_id", callId)
                    putExtra("CALLER_NAME", callerName)
                    putExtra("CALL_ID", callId)
                    putExtra("type", "incoming_call")
                    putExtra("call_action", "answer")
                    putExtra("ACTION", "ANSWER")
                }
                startActivity(actIntent)
                finish()
            }
        }
        buttonRow.addView(answerBtn)

        rootLayout.addView(buttonRow)
        setContentView(rootLayout)

        // 45 second por kono action na hole swayongkriyobhabe dismiss
        handler.postDelayed(timeoutRunnable, 45_000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // CallActivity already khola thakle 2nd call ele purono caller na dekhiye notun caller dekhao
        callerName = intent.getStringExtra("caller_name")
            ?: intent.getStringExtra("CALLER_NAME")
                    ?: callerName
        callId = intent.getStringExtra("call_id")
            ?: intent.getStringExtra("CALL_ID")
                    ?: callId
        if (::nameText.isInitialized) {
            nameText.text = callerName
        }
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 45_000L)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        super.onDestroy()
    }
}
