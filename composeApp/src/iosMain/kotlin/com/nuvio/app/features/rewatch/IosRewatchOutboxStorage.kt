package com.nuvio.app.features.rewatch

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/**
 * New isolated per-profile replay outbox, not the existing Simkl/Continue
 * Watching preference keys. No migration or deletion of old settings.
 *
 * A replay bearer credential must NEVER be stored here (use Keychain).
 */
internal object IosRewatchOutboxStorage : RewatchOutboxStorage {
    private const val PREFIX = "nuvio_rewatch_vps_outbox_v1"
    private const val INSTALL_PREFIX = "nuvio_rewatch_installation_uuid_v1"

    override suspend fun read(profileId: Int): String? {
        require(profileId > 0)
        return NSUserDefaults.standardUserDefaults.stringForKey("${PREFIX}_$profileId")
    }

    override suspend fun write(profileId: Int, payload: String) {
        require(profileId > 0)
        NSUserDefaults.standardUserDefaults.setObject(
            payload, forKey = "${PREFIX}_$profileId"
        )
    }

    /** Generated once per local Nuvio profile; stable across in-place updates. */
    fun installationId(profileId: Int): String {
        require(profileId > 0)
        val key = "${INSTALL_PREFIX}_$profileId"
        val defaults = NSUserDefaults.standardUserDefaults
        val existing = defaults.stringForKey(key)
        if (isValidRewatchInstallationUuid(existing)) return requireNotNull(existing)
        // Do not overwrite a malformed existing identity: queued events may
        // already be signed with its previous server-side device ID.
        require(existing == null) { "Corrupt saved replay installation identity" }
        val created = NSUUID().UUIDString
        require(isValidRewatchInstallationUuid(created))
        defaults.setObject(created, forKey = key)
        return created
    }
}
