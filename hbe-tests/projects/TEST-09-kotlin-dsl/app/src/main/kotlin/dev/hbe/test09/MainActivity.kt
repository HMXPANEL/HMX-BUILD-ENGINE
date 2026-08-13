package dev.hbe.test09

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Kotlin DSL Works"
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }
        setContentView(
            MaterialButton(this).apply {
                text = "Kotlin DSL Button"
                setOnClickListener { tv.text = "Clicked (Kotlin DSL)" }
            }
        )
    }
}