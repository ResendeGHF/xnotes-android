package com.xnotes.share.db

import android.content.Context
import androidx.room.Room
import com.xnotes.share.SharePolicy
import com.xnotes.share.companion.CompanionPins
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paired companions and their per-companion file/folder grants, in Room.
 *
 * One row per companion (keyed by cert fingerprint), one row per grant.
 * The UI observes [devices] / [grants]; the sync engine reads through
 * [DeviceGrants] snapshots so the hot paths never touch disk.
 */
class ShareStore private constructor(
    private val db: ShareDatabase,
    private val dir: File,
) : GrantLookup {
    private val dao get() = db.shareDao()

    val devices: Flow<List<DeviceEntity>> = dao.devices()
    val activeDevice: Flow<DeviceEntity?> = dao.activeDevice()

    suspend fun activeDeviceOnce(): DeviceEntity? = dao.activeDeviceOnce()

    fun grants(fp: String): Flow<List<GrantEntity>> = dao.grants(fp)

    suspend fun device(fp: String): DeviceEntity? = dao.device(fp)

    suspend fun devicesOnce(): List<DeviceEntity> = dao.devicesOnce()

    override suspend fun grantIds(fp: String): List<String> = dao.grantIds(fp)

    /**
     * Remember a companion (pairing, reconnect with new address/name).
     * [makeActive] makes the first companion ever the active dial target.
     */
    suspend fun remember(fp: String, name: String, host: String, port: Int, makeActive: Boolean = false, kind: String? = null) {
        val now = System.currentTimeMillis()
        val cur = dao.device(fp)
        val keepNick = cur?.nickname
        val wasActive = cur?.active ?: false
        dao.upsertDevice(
            DeviceEntity(fp, name.ifBlank { cur?.name ?: "" }, host, port, keepNick, now, wasActive, kind ?: cur?.kind ?: DeviceKind.LINUX),
        )
        if (!wasActive && makeActive) {
            // First companion ever: make it active so existing setups keep working untouched.
            if (dao.devicesOnce().size == 1) dao.markActive(fp)
        }
    }

    suspend fun setActive(fp: String) {
        dao.clearActive()
        dao.markActive(fp)
    }

    /** Forget a companion: device row, its grants, and (by the caller) its TLS pin. */
    suspend fun forget(fp: String) {
        dao.clearGrants(fp)
        dao.deleteDevice(fp)
    }

    /**
     * Revoke every grant naming [rootUri] or anything beneath it, across all
     * devices. Call this BEFORE deleting: document ids are path-derived, so a
     * same-named file recreated later would otherwise inherit the dead file's
     * shares (matching purgeDeleted's view-state cleanup).
     */
    suspend fun revokeSubtree(rootUri: String) {
        val (auth, rootId) = try {
            val u = android.net.Uri.parse(rootUri)
            (u.authority ?: return) to
                (android.provider.DocumentsContract.getDocumentId(u) ?: return)
        } catch (_: Exception) {
            return
        }
        for (g in runCatching { dao.allGrants() }.getOrDefault(emptyList())) {
            if (g.fileId == rootUri) {
                runCatching { dao.removeGrant(g.deviceFp, g.fileId) }
                continue
            }
            val id = runCatching {
                val u = android.net.Uri.parse(g.fileId)
                if (u.authority != auth) null
                else android.provider.DocumentsContract.getDocumentId(u)
            }.getOrNull() ?: continue
            if (id == rootId || id.startsWith("$rootId/")) {
                runCatching { dao.removeGrant(g.deviceFp, g.fileId) }
            }
        }
    }

    suspend fun rename(fp: String, nickname: String?) {
        dao.rename(fp, nickname?.takeIf { it.isNotBlank() })
    }

    suspend fun touch(fp: String, host: String, port: Int, name: String) {
        dao.touch(fp, host, port, name, System.currentTimeMillis())
    }

    override suspend fun setGranted(fp: String, uri: String, granted: Boolean) {
        if (granted) dao.addGrant(GrantEntity(fp, uri)) else dao.removeGrant(fp, uri)
    }

    /**
     * One-time import from the pre-Room files: pins become devices (the last
     * dialed address goes to the sole pin, if there is exactly one), and the
     * old global share set becomes a grant for every imported device, so
     * nothing the companion could see stops being shared.
     */
    suspend fun migrateLegacy(lastHost: String, lastPort: Int) {
        // Pure plan first (unit-tested), then apply it.
        val sentinel = File(dir, ".room_migrated")
        if (sentinel.exists()) return
        val (rawPins, rawShared) = runCatching {
            CompanionPins.of(dir).all() to SharePolicy.of(dir).sharedUris()
        }.getOrDefault(emptyList<CompanionPins.Pin>() to emptySet())
        val plan = migrationPlan(rawPins, rawShared, lastHost, lastPort)
        if (plan == null) {
            runCatching { sentinel.writeText("1") }
            return
        }
        try {
            for (d in plan.devices) dao.upsertDevice(d)
            for (g in plan.grants) dao.addGrant(g)
            if (plan.makeActiveFp != null) dao.markActive(plan.makeActiveFp)
        } finally {
            runCatching { sentinel.writeText("1") }
        }
    }

    companion object {
        @Volatile private var instance: ShareStore? = null

        fun of(context: Context): ShareStore = instance ?: synchronized(this) {
            instance ?: run {
                val dir = File(context.filesDir, "share").apply { mkdirs() }
                val db = Room.databaseBuilder(context.applicationContext, ShareDatabase::class.java, File(dir, "share.db").absolutePath)
                    .addMigrations(ShareMigration1To2)
                    .fallbackToDestructiveMigration()
                    .build()
                ShareStore(db, dir).also { instance = it }
            }
        }

        /** Test seam: an empty in-memory store (Room needs Android, so tests use fakes instead). */
        internal fun resetForTests() {
            instance = null
        }
    }
}

/** One-time import from the pre-Room files, as pure data (unit-tested). */
internal data class MigrationPlan(
    val devices: List<DeviceEntity>,
    val grants: List<GrantEntity>,
    /** Fingerprint to activate (the first imported companion). */
    val makeActiveFp: String?,
)

internal fun migrationPlan(
    pins: List<CompanionPins.Pin>,
    shared: Set<String>,
    lastHost: String,
    lastPort: Int,
): MigrationPlan? {
    if (pins.isEmpty() && shared.isEmpty()) return null
    val single = pins.size == 1
    val devices = pins.map { pin ->
        DeviceEntity(
            fp = pin.fp,
            name = pin.name,
            host = if (single) lastHost else "",
            port = if (single) lastPort else 0,
            lastSeen = 0L,
            active = false,
        )
    }
    // Nothing was shared with anyone: no grants, but still register the device.
    val grants = pins.flatMap { pin -> shared.map { GrantEntity(pin.fp, it) } }
    return MigrationPlan(devices, grants, pins.firstOrNull()?.fp)
}
