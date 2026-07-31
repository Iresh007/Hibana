package com.opennovel.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NovelEntity::class, ChapterEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NovelReaderDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        @Volatile
        private var instance: NovelReaderDatabase? = null

        fun get(context: Context): NovelReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovelReaderDatabase::class.java,
                    "novelreader.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
