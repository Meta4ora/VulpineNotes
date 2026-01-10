package com.example.vulpinenotes

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.TextView

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // устанавливаем splash как стартовую активность
        installSplashScreen()

        setContentView(R.layout.activity_splash)

        val textView = findViewById<TextView>(R.id.splash_text)
        textView.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(300)
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000) // 2 сек
    }
}