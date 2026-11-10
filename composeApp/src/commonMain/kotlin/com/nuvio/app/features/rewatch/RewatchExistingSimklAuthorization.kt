package com.nuvio.app.features.rewatch

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklConnectionMode

/**
 * Read-only adapter to the Simkl authorization already saved in Nuvio.
 *
 * In-place IPA upgrades reuse exactly the existing profile-scoped login.
 * There is no OAuth callback, browser popup, account migration, or Simkl
 * watched-history write here.
 */
internal object RewatchExistingSimklAuthorization {
    fun snapshot(): ExistingSimklRewatchAuthorization? {
        val profileBefore = ProfileRepository.activeProfileId
        if (profileBefore <= 0) return null
        val state = SimklAuthRepository.snapshot()
        if (state.mode != SimklConnectionMode.CONNECTED) return null
        val token = SimklAuthRepository.authorizedAccessToken() ?: return null
        if (ProfileRepository.activeProfileId != profileBefore) return null
        val currentState = SimklAuthRepository.snapshot()
        if (currentState.mode != SimklConnectionMode.CONNECTED ||
            currentState.accountId != state.accountId ||
            SimklAuthRepository.authorizedAccessToken() != token ||
            ProfileRepository.activeProfileId != profileBefore
        ) return null
        return ExistingSimklRewatchAuthorization(
            profileId = profileBefore,
            accessToken = token,
            accountId = state.accountId
        )
    }
}
