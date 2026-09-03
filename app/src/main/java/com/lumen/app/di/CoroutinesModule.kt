package com.lumen.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Scope for work that must outlive the ViewModel that started it — e.g. the
 * onboarding folder add (its ViewModel is cleared by the popUpTo navigation
 * mid-write) and reading-progress saves on viewer exit.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * CPU-bound work dispatcher — search ranking, snippet building. Injected so
 * tests can substitute a recording dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComputeDispatcher

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @ComputeDispatcher
    fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
