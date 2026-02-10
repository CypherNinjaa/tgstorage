package com.tgstorage

import android.app.Application
import androidx.room.Room
import com.tgstorage.data.local.TgStorageDatabase

class TgStorageApp : Application() {

    lateinit var database: TgStorageDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = Room.databaseBuilder(
            applicationContext,
            TgStorageDatabase::class.java,
            TgStorageDatabase.DATABASE_NAME,
        ).build()
    }

    companion object {
        lateinit var instance: TgStorageApp
            private set
    }
}
