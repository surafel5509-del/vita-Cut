package com.vitacut.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitacut.core.database.entity.ExportRecordEntity
import com.vitacut.core.database.entity.ExportStatus
import com.vitacut.core.database.entity.ProjectEntity
import com.vitacut.core.database.entity.ProjectStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test for the Room layer: DAO contracts the data repositories rely on
 * (autosave pending/commit flow, crash-recovery query, export records).
 */
@RunWith(AndroidJUnit4::class)
class VitaDatabaseTest {

    private lateinit var db: VitaDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VitaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() = db.close()

    private fun project(id: String, name: String, updatedAt: Long) = ProjectEntity(
        projectId = id,
        name = name,
        projectJson = """{"id":"$id"}""",
        durationUs = 5_000_000L,
        resolutionLabel = "1080p",
        canvasAspect = 16f / 9f,
        status = ProjectStatus.ACTIVE,
        createdAtMs = updatedAt,
        updatedAtMs = updatedAt,
    )

    @Test
    fun insertAndFindProject() = runBlocking {
        db.projectDao().upsert(project("p1", "First", 100L))
        val found = db.projectDao().findById("p1")
        assertNotNull(found)
        assertEquals("First", found!!.name)

        val all = db.projectDao().observeAll().first()
        assertEquals(1, all.size)
    }

    @Test
    fun projectsAreOrderedByRecency() = runBlocking {
        db.projectDao().upsertAll(
            listOf(
                project("old", "Old", 100L),
                project("new", "New", 300L),
                project("mid", "Mid", 200L),
            ),
        )
        val all = db.projectDao().observeAll().first()
        assertEquals(listOf("new", "mid", "old"), all.map { it.projectId })
    }

    @Test
    fun pendingAutosaveFlowAndCrashRecovery() = runBlocking {
        db.projectDao().upsert(project("p1", "Doc", 100L))

        // No pending state → nothing recoverable.
        assertNull(db.projectDao().findRecoverable())

        // Autosave writes the pending snapshot.
        db.projectDao().savePending("p1", """{"pending":true}""", 200L)
        val recoverable = db.projectDao().findRecoverable()
        assertNotNull(recoverable)
        assertEquals("p1", recoverable!!.projectId)
        assertEquals("""{"pending":true}""", recoverable.pendingJson)

        // Committing promotes pending → committed and clears the pending slot.
        db.projectDao().commitPending("p1", """{"committed":true}""", 300L)
        val committed = db.projectDao().findById("p1")
        assertEquals("""{"committed":true}""", committed!!.projectJson)
        assertNull(committed.pendingJson)
        assertNull(db.projectDao().findRecoverable())
    }

    @Test
    fun renameDeleteAndCount() = runBlocking {
        db.projectDao().upsert(project("p1", "Before", 100L))
        db.projectDao().rename("p1", "After", 200L)
        assertEquals("After", db.projectDao().findById("p1")!!.name)
        assertEquals(1, db.projectDao().count())

        db.projectDao().deleteById("p1")
        assertEquals(0, db.projectDao().count())
        assertNull(db.projectDao().findById("p1"))
    }

    @Test
    fun searchMatchesName() = runBlocking {
        db.projectDao().upsertAll(
            listOf(
                project("p1", "Birthday Party", 100L),
                project("p2", "Travel Vlog", 200L),
            ),
        )
        val results = db.projectDao().search("travel").first()
        assertEquals(listOf("p2"), results.map { it.projectId })
    }

    @Test
    fun exportRecordLifecycle() = runBlocking {
        val recordDao = db.exportRecordDao()
        val id = recordDao.insert(
            ExportRecordEntity(projectId = "p1", exportedAtMs = 100L, resolutionLabel = "1080p"),
        )
        assertTrue(id > 0L)

        // Incomplete query drives the cleanup-on-launch resilience path.
        assertEquals(1, recordDao.findIncomplete().size)

        recordDao.updateStatus(id, ExportStatus.RUNNING)
        assertEquals(ExportStatus.RUNNING, recordDao.findById(id)!!.status)

        recordDao.markSucceeded(id, ExportStatus.SUCCEEDED, null, "content://media/1", 1024L)
        val done = recordDao.findById(id)!!
        assertEquals(ExportStatus.SUCCEEDED, done.status)
        assertEquals("content://media/1", done.publishedUri)
        assertEquals(1024L, done.fileSizeBytes)
        assertTrue(recordDao.findIncomplete().isEmpty())

        val forProject = recordDao.observeForProject("p1").first()
        assertEquals(1, forProject.size)

        recordDao.insert(
            ExportRecordEntity(
                projectId = "p1",
                status = ExportStatus.FAILED,
                errorMessage = "export_error_encoder",
                exportedAtMs = 200L,
            ),
        )
        recordDao.deleteFailed()
        assertEquals(1, recordDao.observeAll().first().size)
    }
}
