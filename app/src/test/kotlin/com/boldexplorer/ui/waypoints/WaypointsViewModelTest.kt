package com.boldexplorer.ui.waypoints

import com.boldexplorer.db.CollectionRepositoryImpl
import com.boldexplorer.db.TrailRepositoryImpl
import com.boldexplorer.db.WaypointRepositoryImpl
import com.boldexplorer.db.createTestDatabase
import com.boldexplorer.location.SelectedCollectionHolder
import com.boldexplorer.location.TargetingStateHolder
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeLocationProvider : LocationProvider {
    override val locationFlow: Flow<LocationSample> = emptyFlow()
}

private class FakeSettingsRepository : SettingsRepository {
    override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings())

    override suspend fun load(): AppSettings = AppSettings()

    override suspend fun save(settings: AppSettings) {}
}

class WaypointsViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun trails_scopedToTheSelectedCollection() =
        runTest {
            val db = createTestDatabase()
            val collections = CollectionRepositoryImpl(db)
            val trailRepo = TrailRepositoryImpl(db)
            val waypointRepo = WaypointRepositoryImpl(db)

            val homeId = collections.create("Home", null)
            val lakeId = collections.create("Lake Loop", null)
            trailRepo.create(homeId, "Driveway", null)
            trailRepo.create(lakeId, "Lake Loop Trail", null)

            val selectedCollectionHolder = SelectedCollectionHolder()
            selectedCollectionHolder.select(lakeId)

            val viewModel =
                WaypointsViewModel(
                    waypointRepo = waypointRepo,
                    collectionRepo = collections,
                    targetingStateHolder = TargetingStateHolder(),
                    selectedCollectionHolder = selectedCollectionHolder,
                    settingsRepo = FakeSettingsRepository(),
                    locationProvider = FakeLocationProvider(),
                )

            // stateIn's WhileSubscribed seed is an empty placeholder emitted before the DB query
            // lands; wait past it rather than reading .value, which would race the query.
            val trails = viewModel.trails.first { it.isNotEmpty() }

            assertEquals(
                listOf("Lake Loop Trail"),
                trails.map { it.name },
                "the attach-to-trail picker must only offer trails from the selected collection",
            )
        }
}
