package com.nuvio.app.features.rewatch

/** No shared keys with the existing Nuvio/Simkl data stores. */
internal expect object RewatchSessionStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}
