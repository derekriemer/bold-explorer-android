package com.boldexplorer.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/** Creates an in-memory BoldExplorerDatabase for unit tests. */
fun createTestDatabase(): BoldExplorerDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    BoldExplorerDatabase.Schema.create(driver)
    return BoldExplorerDatabase(driver)
}

/**
 * Creates a throwaway collection for tests that need a collection id to satisfy the
 * "every user waypoint/trail belongs to a collection" invariant.
 */
suspend fun BoldExplorerDatabase.defaultCollection(name: String = "Test"): Long = CollectionRepositoryImpl(this).create(name, null)
