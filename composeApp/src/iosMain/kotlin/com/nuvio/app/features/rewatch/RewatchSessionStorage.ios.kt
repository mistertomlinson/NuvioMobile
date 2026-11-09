package com.nuvio.app.features.rewatch

import platform.Foundation.NSUserDefaults

internal actual object RewatchSessionStorage {
    private const val prefix = "nuvio_rewatch_sessions_v1"

    actual fun loadPayload(profileId: Int): String? {
        require(profileId > 0)
        return NSUserDefaults.standardUserDefaults.stringForKey("${prefix}_$profileId")
    }

    actual fun savePayload(profileId: Int, payload: String) {
        require(profileId > 0)
        NSUserDefaults.standardUserDefaults.setObject(
            payload, forKey = "${prefix}_$profileId"
        )
    }
}
