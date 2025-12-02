package com.example.vulpinenotes

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.example.vulpinenotes.LocaleHelper

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }
}
