package com.cash.transfer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_TEMPLATE = "*9*7*{phone}*{amount}#"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var phone: EditText
    private lateinit var amount: EditText
    private lateinit var template: EditText
    private lateinit var error: TextView
    private lateinit var preview: TextView
    private lateinit var chips: LinearLayout
    private var pendingUssd: String? = null

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingUssd?.let { dial(it, granted) }
            pendingUssd = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cash", Context.MODE_PRIVATE)
        buildUi()
        renderChips()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 22f
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.END
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(40), dp(22), dp(30))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply {
            text = "تحويل فودافون كاش"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "اكتب الرقم والمبلغ، والـ PIN هتكتبه بنفسك."
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(label("رقم المستلم"))
        phone = field("01xxxxxxxxx", InputType.TYPE_CLASS_PHONE)
        root.addView(phone)

        root.addView(label("المبلغ بالجنيه"))
        amount = field("0", InputType.TYPE_CLASS_NUMBER)
        root.addView(amount)

        error = TextView(this).apply {
            setTextColor(Color.parseColor("#E60000"))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(error)

        val go = Button(this).apply {
            text = "تحويل"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60000"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { onTransfer() }
        }
        root.addView(go, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { topMargin = dp(12) })

        preview = TextView(this).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(10), 0, 0)
            alpha = 0.7f
        }
        root.addView(preview)

        root.addView(label("أرقام بتحوّل لها كتير"))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(chips) })

        root.addView(label("كود فودافون (عدّله لو احتجت)"))
        template = field(DEFAULT_TEMPLATE, InputType.TYPE_CLASS_TEXT).apply {
            textSize = 16f
            setText(prefs.getString("template", DEFAULT_TEMPLATE))
        }
        root.addView(template)
        root.addView(TextView(this).apply {
            text = "استخدم {phone} مكان الرقم و{amount} مكان المبلغ. لو الكود مشتغلش، اتصل *9# بإيدك وشوف ترتيب الخطوات."
            textSize = 12f
            alpha = 0.7f
        })

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                preview.text = if (digits(phone).isNotEmpty() || digits(amount).isNotEmpty()) build() else ""
            }
        }
        phone.addTextChangedListener(watcher)
        amount.addTextChangedListener(watcher)
        template.addTextChangedListener(watcher)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun digits(e: EditText): String {
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return e.text.toString().map { c ->
            val i = arabic.indexOf(c)
            if (i >= 0) ('0' + i) else c
        }.joinToString("").filter { it.isDigit() }
    }

    private fun build(): String =
        template.text.toString()
            .replace("{phone}", digits(phone))
            .replace("{amount}", digits(amount))

    private fun onTransfer() {
        error.text = ""
        val p = digits(phone)
        val a = digits(amount).toIntOrNull() ?: 0
        if (!Regex("^01[0-9]{9}$").matches(p)) {
            error.text = "الرقم لازم يبقى 11 رقم ويبدأ بـ 01."
            return
        }
        if (a < 1) {
            error.text = "اكتب مبلغ صحيح."
            return
        }
        prefs.edit().putString("template", template.text.toString()).apply()
        saveRecent(p)
        renderChips()

        val ussd = build()
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            dial(ussd, true)
        } else {
            pendingUssd = ussd
            permission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    // لو الإذن مرفوض بنفتح شاشة الاتصال بس، وإنت تدوس اتصال
    private fun dial(ussd: String, direct: Boolean) {
        val uri = Uri.parse("tel:" + Uri.encode(ussd))
        startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
    }

    private fun recent(): List<String> =
        prefs.getString("recent", "")!!.split(",").filter { it.isNotBlank() }

    private fun saveRecent(p: String) {
        val list = (listOf(p) + recent().filter { it != p }).take(6)
        prefs.edit().putString("recent", list.joinToString(",")).apply()
    }

    private fun renderChips() {
        chips.removeAllViews()
        val list = recent()
        if (list.isEmpty()) {
            chips.addView(TextView(this).apply { text = "هتظهر هنا بعد أول تحويل." })
            return
        }
        list.forEach { n ->
            chips.addView(Button(this).apply {
                text = n
                isAllCaps = false
                setOnClickListener { phone.setText(n); amount.requestFocus() }
            })
        }
    }
}
