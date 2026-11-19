package com.example

import android.app.Application
import com.example.data.LkProxyRepository

class LkProxyApplication : Application() {

    lateinit var repository: LkProxyRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LkProxyRepository(this)
    }
}
