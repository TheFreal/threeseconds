package de.freal.threeseconds

import android.app.Application
import android.content.Context
import androidx.room.Room
import de.freal.threeseconds.data.AppDatabase
import de.freal.threeseconds.data.SettingsRepository
import de.freal.threeseconds.glasses.GlassesManager
import de.freal.threeseconds.media.ClipStore
import de.freal.threeseconds.notify.Notifications
import de.freal.threeseconds.schedule.AttemptLog
import de.freal.threeseconds.schedule.DailyScheduler

class ThreeSecondsApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        GlassesManager.initialize(this)
        Notifications.ensureChannels(this)
    }
}

/** Hand-rolled singletons. The graph is small enough that a DI framework would be noise. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "three_seconds.db").build()
    }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val clipStore: ClipStore by lazy { ClipStore(appContext) }

    val attempts: AttemptLog by lazy { AttemptLog(database.attempts()) }

    val scheduler: DailyScheduler by lazy { DailyScheduler(appContext, settings, attempts) }
}

val Context.container: AppContainer
    get() = (applicationContext as ThreeSecondsApp).container
