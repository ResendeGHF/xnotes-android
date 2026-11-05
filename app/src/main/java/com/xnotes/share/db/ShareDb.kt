package com.xnotes.share.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The share bookkeeping database: paired companions and per-companion grants.
 *
 * Why Room and not the old JSON files: the policy screen needs relational
 * queries (which files does companion X see? which companions see file Y?)
 * observed as Flows, with transactions and migrations instead of whole-file
 * rewrites. The dataset is tiny (companions, grants) and fully indexed; the
 * file bytes themselves never touch this database.
 *
 * Threat model note: this database is NOT encrypted. It holds companion
 * addresses, names and grant lists — metadata a paired companion already
 * sees on the wire. The crown jewels (the TLS identity key and the pinned
 * peer fingerprints gating every connection) stay in app-private files with
 * filesystem permissions, and modern Android encrypts storage at rest by
 * default (file-based encryption). SQLCipher would add native blobs and
 * reproducible-build risk for no practical gain here.
 */
@Database(entities = [DeviceEntity::class, GrantEntity::class], version = 2, exportSchema = false)
abstract class ShareDatabase : RoomDatabase() {
    abstract fun shareDao(): ShareDao
}

/** v1 → v2: device kind (old rows are all Linux companions). */
val ShareMigration1To2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN kind TEXT NOT NULL DEFAULT '${DeviceKind.LINUX}'")
    }
}

/** One paired device: the Linux companion (this phone dials it) or an Android phone. */
@androidx.room.Entity(tableName = "devices")
data class DeviceEntity(
    /** Lowercase hex SHA-256 of the peer's cert: the stable identity. */
    @androidx.room.PrimaryKey val fp: String,
    /** The name the peer introduced itself with. */
    val name: String,
    /** Last known address, for redial. */
    val host: String,
    val port: Int,
    /** User alias; shown instead of [name] when set. */
    val nickname: String? = null,
    /** Last successful connect, epoch ms. */
    val lastSeen: Long = 0L,
    /** The companion to dial; the others stay remembered but disconnected. */
    val active: Boolean = false,
    /** [DeviceKind.LINUX] (desktop companion) or [DeviceKind.PHONE] (Android peer). */
    val kind: String = DeviceKind.LINUX,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: name.ifEmpty { "$host:$port" }
}

/** What kind of peer a device row is. */
object DeviceKind {
    /** The desktop Linux companion (dialed when active). */
    const val LINUX = "linux"
    /** An Android phone (guest sessions + inbound hosting). */
    const val PHONE = "phone"
}

/** One shared file-or-folder grant, scoped to a single companion. */
@androidx.room.Entity(tableName = "grants", primaryKeys = ["deviceFp", "fileId"])
data class GrantEntity(
    val deviceFp: String,
    /** Stable file/folder id (URI on the host). A folder id shares its whole subtree. */
    val fileId: String,
)

@Dao
interface ShareDao {
    @Query("SELECT * FROM devices ORDER BY active DESC, lastSeen DESC")
    fun devices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE active != 0 LIMIT 1")
    fun activeDevice(): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE active != 0 LIMIT 1")
    suspend fun activeDeviceOnce(): DeviceEntity?

    @Query("SELECT * FROM devices ORDER BY active DESC, lastSeen DESC")
    suspend fun devicesOnce(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE fp = :fp")
    suspend fun device(fp: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(d: DeviceEntity)

    @Query("UPDATE devices SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE devices SET active = 1 WHERE fp = :fp")
    suspend fun markActive(fp: String)

    @Query("UPDATE devices SET nickname = :nickname WHERE fp = :fp")
    suspend fun rename(fp: String, nickname: String?)

    @Query("UPDATE devices SET host = :host, port = :port, name = :name, lastSeen = :now WHERE fp = :fp")
    suspend fun touch(fp: String, host: String, port: Int, name: String, now: Long)

    @Query("DELETE FROM devices WHERE fp = :fp")
    suspend fun deleteDevice(fp: String)

    @Query("SELECT * FROM grants WHERE deviceFp = :fp")
    fun grants(fp: String): Flow<List<GrantEntity>>

    @Query("SELECT fileId FROM grants WHERE deviceFp = :fp")
    suspend fun grantIds(fp: String): List<String>

    @Query("SELECT deviceFp FROM grants WHERE fileId = :fileId")
    suspend fun grantedDevices(fileId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addGrant(g: GrantEntity)

    @Query("DELETE FROM grants WHERE deviceFp = :fp AND fileId = :fileId")
    suspend fun removeGrant(fp: String, fileId: String)

    @Query("DELETE FROM grants WHERE deviceFp = :fp")
    suspend fun clearGrants(fp: String)
}
