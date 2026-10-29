package com.rdp.sync.security

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rdp.sync.data.Device
import com.rdp.sync.database.DeviceDatabase
import com.rdp.sync.repository.DeviceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialStorageInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val alias = "rdpsync.test.$suffix"
    private val cipher = AndroidCredentialCipher(alias)
    private val databases = mutableListOf<DeviceDatabase>()
    private val names = mutableListOf<String>()
    private fun db(name: String = "credential-test-$suffix"): DeviceDatabase {
        names += name
        return Room.databaseBuilder(context, DeviceDatabase::class.java, name)
            .addMigrations(DeviceDatabase.MIGRATION_1_2, DeviceDatabase.MIGRATION_2_3).build().also { databases += it }
    }
    private fun device(id: Long = 0, password: String = "synthetic-password-$suffix") =
        Device(id=id, name="Fixture", host="fixture.invalid", username="fixture", password=password, width=1440, height=900)

    @After fun cleanup() {
        databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) }
        context.getSharedPreferences("credential-test-$suffix", Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun realKeystoreEncryptsAllWritePathsAndRestartsWithoutPlaintextRows() = runBlocking {
        val db = db(); val repo = DeviceRepository(db, cipher)
        val id = repo.insertDevice(device())
        assertEquals(device().password, repo.getDeviceById(id)!!.password)
        var raw = db.deviceDao().getDeviceById(id)!!
        assertEquals(1, raw.passwordFormat); assertNotEquals(device().password, raw.password)
        repo.updateDevice(device(id, "updated-$suffix"))
        repo.importDevices(listOf(device(password="imported-$suffix")))
        raw = db.deviceDao().getDeviceById(id)!!
        assertEquals(id, raw.id); assertFalse(raw.password.contains("imported"))
        assertFalse(db.deviceDao().getSnapshot().joinToString { it.password }.contains("imported-$suffix"))
        db.close(); databases.remove(db)
        val bytesOnDisk = context.getDatabasePath("credential-test-$suffix").readBytes().toString(Charsets.ISO_8859_1)
        listOf(device().password, "updated-$suffix", "imported-$suffix").forEach { assertFalse(bytesOnDisk.contains(it)) }
        val reopened = db()
        assertEquals("imported-$suffix", DeviceRepository(reopened, AndroidCredentialCipher(alias)).getDeviceById(id)!!.password)
    }

    @Test fun v2MigrationPreservesIdsMetadataIsAtomicAndIdempotent() = runBlocking {
        val name = "credential-v2-$suffix"; names += name
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { sql ->
            sql.execSQL("CREATE TABLE devices (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, domain TEXT NOT NULL, rdpServerName TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL)")
            sql.execSQL("INSERT INTO devices VALUES (41,'Fixture','fixture.invalid',3389,'fixture',?,'','server',1440,900)", arrayOf(device().password))
            sql.version = 2
        }
        val db = db(name); val repo = DeviceRepository(db, cipher)
        repo.migrateLegacy()
        val raw = db.deviceDao().getDeviceById(41)!!
        assertEquals(1, raw.passwordFormat); assertNotEquals(device().password, raw.password)
        assertEquals(1440, raw.width); assertEquals("server", raw.rdpServerName)
        repo.migrateLegacy(); assertEquals(raw, db.deviceDao().getDeviceById(41))
        assertEquals(device().password, repo.getDeviceById(41)!!.password)
    }

    @Test fun partialMigrationFailureLeavesEveryOriginalRowAndRetryWorks() = runBlocking {
        val db = db(); val dao = db.deviceDao()
        dao.insertDevice(device(1)); dao.insertDevice(device(2).copy(host="other.invalid"))
        var calls = 0
        val failing = object : CredentialCipher {
            override fun encrypt(plaintext: String, context: String): String {
                if (++calls == 2) throw CredentialStorageException()
                return cipher.encrypt(plaintext, context)
            }
            override fun decrypt(envelope: String, context: String) = cipher.decrypt(envelope, context)
        }
        try { DeviceRepository(db, failing).migrateLegacy(); fail("migration must roll back") } catch (_: CredentialStorageException) { }
        dao.getSnapshot().forEach { assertEquals(0, it.passwordFormat); assertEquals(device().password, it.password) }
        DeviceRepository(db, cipher).migrateLegacy()
        assertTrue(dao.getSnapshot().all { it.passwordFormat == 1 })
    }

    @Test fun keyLossAndCorruptionPreserveBytesMetadataAndRejectSilentEmptyReplacement() = runBlocking {
        val db = db(); val repo = DeviceRepository(db, cipher); val id = repo.insertDevice(device())
        val raw = db.deviceDao().getDeviceById(id)!!
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        val locked = repo.getAllDevices().first().single()
        assertTrue(locked.credentialUnavailable); assertEquals("Fixture", locked.name); assertEquals(id, locked.id)
        try { repo.updateDevice(locked.copy(password="")); fail("empty recovery forbidden") } catch (_: CredentialStorageException) { }
        repo.importDevices(listOf(device(password="").apply { passwordProvided = false }))
        assertEquals(raw.password, db.deviceDao().getDeviceById(id)!!.password)
        repo.updateDevice(device(id, "deliberately-reentered"))
        assertEquals("deliberately-reentered", repo.getDeviceById(id)!!.password)
        val corrupt = db.deviceDao().getDeviceById(id)!!.copy(password="corrupt")
        db.deviceDao().updateDevice(corrupt)
        assertTrue(repo.getAllDevices().first().single().credentialUnavailable)
        assertEquals(corrupt, db.deviceDao().getDeviceById(id))
    }

    @Test fun passwordlessImportPreservesLocalSecretAndIdButExplicitEmptyIsRespected() = runBlocking {
        val db = db(); val repo = DeviceRepository(db, cipher); val id = repo.insertDevice(device())
        repo.importDevices(listOf(device(password="").copy(name="Renamed").apply { passwordProvided=false }))
        assertEquals(device().password, repo.getDeviceById(id)!!.password)
        assertEquals("Renamed", repo.getDeviceById(id)!!.name)
        repo.importDevices(listOf(device(password="")))
        assertEquals("", repo.getDeviceById(id)!!.password)
        repo.importDevices(emptyList()); assertEquals(1, repo.getAllDevices().first().size)
    }

    @Test fun webDavLegacyMigrationEncryptsAndKeyLossNeverClearsCiphertext() {
        val prefs = context.getSharedPreferences("credential-test-$suffix", Context.MODE_PRIVATE)
        prefs.edit().putString(SecureWebDavSettings.LEGACY, "webdav-synthetic-$suffix").putString("webdav_url", "https://fixture.invalid/").commit()
        val store = SecureWebDavSettings(prefs, cipher)
        assertEquals("webdav-synthetic-$suffix", store.load().password)
        assertFalse(prefs.contains(SecureWebDavSettings.LEGACY))
        assertFalse(prefs.all.toString().contains("webdav-synthetic-$suffix"))
        val xml = java.io.File(context.applicationInfo.dataDir, "shared_prefs/credential-test-$suffix.xml").readText()
        assertFalse(xml.contains("webdav-synthetic-$suffix"))
        val before = prefs.getString(SecureWebDavSettings.ENCRYPTED, null)
        assertEquals(store.load(), SecureWebDavSettings(prefs, AndroidCredentialCipher(alias)).load())
        assertEquals(before, prefs.getString(SecureWebDavSettings.ENCRYPTED, null))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertTrue(store.load().unavailable)
        try { store.save("https://fixture.invalid/", "fixture", ""); fail("must not erase") } catch (_: CredentialStorageException) { }
        assertEquals(before, prefs.getString(SecureWebDavSettings.ENCRYPTED, null))
        store.save("https://fixture.invalid/", "fixture", "deliberate-reentry")
        assertEquals("deliberate-reentry", store.load().password)
    }
    @Test fun preferencesPartialMarkerAndCorruptionNeverBecomeUnconfiguredEmptyPassword() {
        val prefs = context.getSharedPreferences("credential-test-$suffix", Context.MODE_PRIVATE)
        prefs.edit().putInt(SecureWebDavSettings.FORMAT, 1).commit()
        val store = SecureWebDavSettings(prefs, cipher)
        assertTrue(store.load().unavailable)
        prefs.edit().putString(SecureWebDavSettings.ENCRYPTED, "invalid").commit()
        assertTrue(store.load().unavailable)
        val before = prefs.all.toMap()
        try { store.save("https://fixture.invalid", "fixture", ""); fail("blank must not overwrite corrupt state") } catch (_: CredentialStorageException) { }
        assertEquals(before, prefs.all)
    }

    @Test fun preferencesCommitFailurePreservesLegacyDataForRetry() {
        val prefs = context.getSharedPreferences("credential-test-$suffix", Context.MODE_PRIVATE)
        prefs.edit().putString(SecureWebDavSettings.LEGACY, "legacy-synthetic").commit()
        val before = prefs.all.toMap()
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { delegate.putInt(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit() = false
                }
            }
        }
        assertTrue(SecureWebDavSettings(failing, cipher).load().unavailable)
        assertEquals(before, prefs.all)
        assertEquals("legacy-synthetic", SecureWebDavSettings(prefs, cipher).load().password)
        assertFalse(prefs.contains(SecureWebDavSettings.LEGACY))
    }

    @Test fun mergeUsesCurrentLocalStateAndNeverResurrectsDeletedSnapshots() = runBlocking {
        val db = db(); val repo = DeviceRepository(db, cipher)
        val id = repo.insertDevice(device())
        val snapshot = repo.getDeviceById(id)!!
        repo.updateDevice(device(id, "newer-local-secret"))
        repo.importDevices(listOf(snapshot), mergeOnlyNew = true)
        assertEquals("newer-local-secret", repo.getDeviceById(id)!!.password)
        // A remote row that collided with a newly-added local identity also loses in MERGE.
        repo.importDevices(listOf(device(password="older-remote-secret")), mergeOnlyNew = true)
        assertEquals("newer-local-secret", repo.getDeviceById(id)!!.password)
        repo.deleteDevice(snapshot)
        repo.importDevices(listOf(snapshot), mergeOnlyNew = true)
        assertTrue(repo.getAllDevices().first().isEmpty())
    }

    @Test fun newlyOmittedSecretRemainsMissingAfterDatabaseReopenUntilExplicitEntry() = runBlocking {
        val first = db(); val repo = DeviceRepository(first, cipher)
        repo.importDevices(listOf(device(password="").apply { passwordProvided = false }))
        val row = first.deviceDao().getSnapshot().single()
        assertEquals(2, row.passwordFormat); assertEquals("", row.password)
        first.close(); databases.remove(first)
        val reopened = db(); val restored = DeviceRepository(reopened, AndroidCredentialCipher(alias))
        val missing = restored.getAllDevices().first().single()
        assertTrue(missing.credentialUnavailable); assertFalse(missing.passwordProvided); assertEquals(2, missing.passwordFormat)
        // An explicit legacy empty password is meaningful, unlike omitted password.
        restored.importDevices(listOf(device(password="")))
        val intentionalEmpty = restored.getAllDevices().first().single()
        assertFalse(intentionalEmpty.credentialUnavailable); assertTrue(intentionalEmpty.passwordProvided)
        assertEquals("", intentionalEmpty.password)
    }

    @Test fun failedCommitMutatingCacheCannotMasqueradeAsDurableSuccessAcrossWrappers() {
        val prefs = context.getSharedPreferences("credential-test-$suffix", Context.MODE_PRIVATE)
        prefs.edit().putString(SecureWebDavSettings.LEGACY, "legacy-cache-fixture").commit()
        var failCommit = true
        val cacheMutatingFailure = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { delegate.putInt(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit(): Boolean { val success = delegate.commit(); return success && !failCommit }
                }
            }
        }
        assertTrue(SecureWebDavSettings(cacheMutatingFailure, cipher).load().unavailable)
        assertTrue(prefs.contains(SecureWebDavSettings.ENCRYPTED)) // Simulates cache mutation before false return.
        assertTrue(SecureWebDavSettings(cacheMutatingFailure, cipher).load().unavailable)
        failCommit = false
        assertEquals("legacy-cache-fixture", SecureWebDavSettings(cacheMutatingFailure, cipher).load().password)
        failCommit = true
        try { SecureWebDavSettings(cacheMutatingFailure, cipher).save("https://fixture.invalid", "fixture", "unsaved-new-value"); fail("save must report failure") } catch (_: CredentialStorageException) { }
        assertTrue(SecureWebDavSettings(cacheMutatingFailure, cipher).load().unavailable)
        failCommit = false
        // Failed explicit saves remain quarantined until the user deliberately saves again.
        assertTrue(SecureWebDavSettings(cacheMutatingFailure, cipher).load().unavailable)
        SecureWebDavSettings(cacheMutatingFailure, cipher).save("https://fixture.invalid", "fixture", "confirmed-retry")
        assertEquals("confirmed-retry", SecureWebDavSettings(cacheMutatingFailure, cipher).load().password)
    }

}
