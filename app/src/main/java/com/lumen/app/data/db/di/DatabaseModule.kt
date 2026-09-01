package com.lumen.app.data.db.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumen.app.data.db.LumenDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LumenDatabase =
        Room.databaseBuilder(context, LumenDatabase::class.java, "lumen.db")
            .addMigrations(
                LumenDatabase.MIGRATION_3_4,
                LumenDatabase.MIGRATION_4_5,
                LumenDatabase.MIGRATION_5_6,
                LumenDatabase.MIGRATION_6_7,
                LumenDatabase.MIGRATION_7_8,
                LumenDatabase.MIGRATION_8_9,
                LumenDatabase.MIGRATION_9_10,
                LumenDatabase.MIGRATION_10_11,
                LumenDatabase.MIGRATION_11_12,
                LumenDatabase.MIGRATION_12_13,
                LumenDatabase.MIGRATION_13_14,
            )
            .fallbackToDestructiveMigration(true)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()

    @Provides
    fun provideDocumentDao(db: LumenDatabase) = db.documentDao()

    @Provides
    fun providePageDao(db: LumenDatabase) = db.pageDao()

    @Provides
    fun providePageTextDao(db: LumenDatabase) = db.pageTextDao()

    @Provides
    fun provideBookmarkDao(db: LumenDatabase) = db.bookmarkDao()

    @Provides
    fun provideDocumentTitleDao(db: LumenDatabase) = db.documentTitleDao()

    @Provides
    fun provideExternalOpenDao(db: LumenDatabase) = db.externalOpenDao()

    @Provides
    fun provideRenameDao(db: LumenDatabase) = db.renameDao()
}
