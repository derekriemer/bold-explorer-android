package com.boldexplorer.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide source of truth for the selected collection. All screens (GPS, Waypoints, Trails) read
 * from and write to this holder so that selecting a collection anywhere propagates everywhere.
 *
 * A singleton because every screen's ViewModel is a distinct Hilt-managed instance; they must all
 * share one flow to stay in sync.
 */
@Singleton
class SelectedCollectionHolder
    @Inject
    constructor() {
        private val _selectedCollectionId = MutableStateFlow<Long?>(null)
        val selectedCollectionId: StateFlow<Long?> = _selectedCollectionId.asStateFlow()

        /** Select [id] as the active collection across the entire app. */
        fun select(id: Long?) {
            _selectedCollectionId.value = id
        }
    }
