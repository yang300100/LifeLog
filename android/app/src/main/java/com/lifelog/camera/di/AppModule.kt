package com.lifelog.camera.di

import android.content.Context
import com.lifelog.camera.data.local.AppDatabase
import com.lifelog.camera.data.local.CharacterStatsDao
import com.lifelog.camera.data.local.SkillProficiencyDao
import com.lifelog.camera.data.local.DiscoveredSkillDao
import com.lifelog.camera.data.repository.VideoRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVideoDir(@ApplicationContext context: Context): File {
        val dir = File(context.filesDir, "videos")
        dir.mkdirs()
        return dir
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return androidx.room.Room.databaseBuilder(
            context, AppDatabase::class.java, "lifelog.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideCharacterStatsDao(db: AppDatabase): CharacterStatsDao = db.characterStatsDao()

    @Provides
    @Singleton
    fun provideSkillProficiencyDao(db: AppDatabase): SkillProficiencyDao = db.skillProficiencyDao()

    @Provides
    @Singleton
    fun provideDiscoveredSkillDao(db: AppDatabase): DiscoveredSkillDao = db.discoveredSkillDao()
}
