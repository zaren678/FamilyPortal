package com.johnanderson.familyportal

import android.app.Application
import androidx.room.Room
import com.johnanderson.familyportal.calendar.CalendarRepository
import com.johnanderson.familyportal.calendar.FamilyPortalDatabase
import com.johnanderson.familyportal.calendar.GoogleAuthManager
import com.johnanderson.familyportal.calendar.GoogleCalendarClient
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.AppCoordinator
import com.johnanderson.familyportal.core.NetworkMonitor
import com.johnanderson.familyportal.core.SecureStore
import com.johnanderson.familyportal.core.SettingsRepository
import com.johnanderson.familyportal.ha.HomeAssistantClient
import com.johnanderson.familyportal.ha.HomeAssistantAuthManager
import com.johnanderson.familyportal.ha.HomeAssistantCatalogClient
import com.johnanderson.familyportal.ha.HomeAssistantDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FamilyPortalApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val secureStore = SecureStore(application)
    val settingsRepository = SettingsRepository(application, secureStore, json)
    val networkMonitor = NetworkMonitor(application)
    val database = Room.databaseBuilder(
        application,
        FamilyPortalDatabase::class.java,
        "family_portal.db",
    ).build()
    val googleAuthManager = GoogleAuthManager(application, secureStore, httpClient)
    val googleCalendarClient = GoogleCalendarClient(httpClient, json, googleAuthManager)
    val calendarRepository = CalendarRepository(database.calendarDao(), googleCalendarClient)
    val homeAssistantAuthManager = HomeAssistantAuthManager(httpClient, json, secureStore)
    val homeAssistantDiscovery = HomeAssistantDiscovery(application)
    val homeAssistantCatalogClient = HomeAssistantCatalogClient(httpClient, json, homeAssistantAuthManager)
    val homeAssistantClient = HomeAssistantClient(httpClient, json)
    val cameraRepository = CameraRepository(httpClient, homeAssistantAuthManager)
    val coordinator = AppCoordinator(applicationScope)

}
