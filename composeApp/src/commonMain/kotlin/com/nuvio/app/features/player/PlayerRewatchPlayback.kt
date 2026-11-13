package com.nuvio.app.features.player

import com.nuvio.app.isIos
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.rewatch.RewatchEpisode
import com.nuvio.app.features.rewatch.RewatchSession
import com.nuvio.app.features.rewatch.RewatchIpadCommitHandoff
import com.nuvio.app.features.rewatch.RewatchManualEpisodeSelection
import com.nuvio.app.features.rewatch.RewatchMobileSyncGate
import com.nuvio.app.features.rewatch.RewatchPlatformSyncLifecycle
import com.nuvio.app.features.rewatch.notifyCommittedRewatchSave
import com.nuvio.app.features.rewatch.RewatchSaveCoordinator
import com.nuvio.app.features.rewatch.RewatchSessionRepository
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.completeRewatchEpisode
import com.nuvio.app.features.rewatch.resolvePendingRewatchSuccessor
import com.nuvio.app.features.rewatch.recordRewatchProgress
import com.nuvio.app.features.rewatch.resolveOrStartRewatchForPlayback
import com.nuvio.app.features.rewatch.resolveRewatchResumePosition
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.isWatchProgressComplete
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

internal val PlayerScreenRuntime.rewatchWriteMode: PlayerRewatchWriteMode
    get() = playerRewatchWriteMode(
        currentKey = activePlaybackKey,
        classifiedKey = rewatchClassifiedKey,
        profileId = profileId,
        classifiedProfileId = rewatchClassifiedProfileId,
        replaySession = activeRewatchSession
    )

/**
 * Run when the playback key changes. Until this suspending classification
 * finishes, every provider scrobble and progress write must be suppressed.
 */
internal suspend fun PlayerScreenRuntime.classifyRewatchBeforeTracking() {
    val key = activePlaybackKey
    val selectedProfile = profileId
    val series = parentMetaId.trim()
    val selectedEpisode = playerRewatchEpisode(activeSeasonNumber, activeEpisodeNumber)

    // This 0.6.7 development branch is for the iPad IPA. Its separate KMP
    // Android flavor has no initialized replay storage; keep Android's
    // existing tracking behavior intact until that flavor is deliberately
    // integrated and tested.
    if (!isIos) {
        activeRewatchSession = null
        rewatchClassifiedProfileId = selectedProfile
        rewatchClassifiedKey = key
        return
    }

    // Earlier replay writes, including an EOF flush before autoplay, must
    // commit before the next episode tries to read its durable cursor.
    RewatchSaveCoordinator.awaitPending()
    if (RewatchMobileSyncGate.ENABLED && series.isNotBlank()) {
        RewatchPlatformSyncLifecycle.beforePlayback(series)
    }
    if (key != activePlaybackKey ||
        selectedProfile != profileId ||
        selectedProfile != ProfileRepository.activeProfileId
    ) return

    // Consume only the explicit click matching this exact profile/show/episode.
    // Autoplay and ordinary resume never arm this marker.
    val manualSelection = selectedEpisode?.let {
        RewatchManualEpisodeSelection.consume(
            selectedProfile, series, it, WatchProgressClock.nowEpochMs()
        )
    }

    val resolved: RewatchSession? = if (
        parentMetaType.trim().lowercase() !in
            setOf("series", "tv", "show", "tvshow") ||
            series.isEmpty() || selectedEpisode == null
    ) null else {
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        resolveOrStartRewatchForPlayback(
            store = RewatchSessionRepository,
            expectedProfileId = selectedProfile,
            activeProfileId = { ProfileRepository.activeProfileId },
            ownerKey = "local-profile:$selectedProfile",
            canonicalSeriesId = series,
            episode = selectedEpisode,
            wasPreviouslyWatched = {
                // A partial or absent provider projection is not proof of a
                // rewatch. Wait briefly for the selected source to load;
                // never manufacture a watched flag from a title match.
                if (!WatchedRepository.uiState.value.isLoaded) {
                    withTimeoutOrNull(1_500L) {
                        WatchedRepository.uiState.first { it.isLoaded }
                    }
                }
                val watched = WatchedRepository.uiState.value
                val fromHistory = watched.isLoaded &&
                    WatchedRepository.isWatched(
                        id = series,
                        type = parentMetaType,
                        season = selectedEpisode.season,
                        episode = selectedEpisode.episode
                    )
                val fromProgress = WatchProgressRepository.progressForVideo(
                    videoId = activeVideoId.orEmpty(),
                    parentMetaId = series,
                    seasonNumber = selectedEpisode.season,
                    episodeNumber = selectedEpisode.episode
                )?.isEffectivelyCompleted == true
                fromHistory || fromProgress
            },
            createSessionId = {
                "ios-$selectedProfile-${WatchProgressClock.nowEpochMs()}-${Random.nextLong()}"
            },
            nowEpochMs = { WatchProgressClock.nowEpochMs() },
            explicitManualEpisodeSelection = manualSelection != null,
            manualResumePositionMs = manualSelection?.resumePositionMs,
            onCommitted = { before, after ->
                notifyCommittedRewatchSave(
                    sink = RewatchIpadCommitHandoff.sink,
                    accepted = true,
                    profileId = selectedProfile,
                    activeProfileId = { ProfileRepository.activeProfileId },
                    before = before,
                    after = after
                )
            }
        )
    }
    if (key != activePlaybackKey ||
        selectedProfile != profileId ||
        selectedProfile != ProfileRepository.activeProfileId
    ) return

    val handoff = pendingRewatchAutoplayHandoff
    if (handoff != null) {
        if (handoff.expectedEpisode == selectedEpisode) {
            check(playerRewatchAutoplayHandoffReady(
                handoff = handoff,
                resolved = resolved,
                requested = selectedEpisode
            )) {
                "Autoplay replay cursor did not advance to the expected next episode"
            }
        }
        // Different manual episode selections must not inherit the automatic
        // successor requirement of the previous episode.
        pendingRewatchAutoplayHandoff = null
    }

    if (resolved != null && selectedEpisode != null) {
        val ownPosition = resolveRewatchResumePosition(
            session = resolved,
            ownerKey = "local-profile:$selectedProfile",
            canonicalSeriesId = series,
            episode = selectedEpisode
        ) ?: 0L
        // Do not consult the original Simkl resume position for a replay.
        activeInitialPositionMs = ownPosition
        activeInitialProgressFraction = null
        initialSeekApplied = ownPosition <= 0L
        if (ownPosition <= 0L &&
            playerControllerSourceUrl == activeSourceUrl &&
            !playbackSnapshot.isLoading
        ) {
            playerController?.seekTo(0L)
        }
    }
    activeRewatchSession = resolved
    rewatchClassifiedProfileId = selectedProfile
    rewatchClassifiedKey = key
}

/**
 * Serialize replay-only writes. Never persist progress into the provider's
 * original watched history for a playback classified as a replay.
 */
internal fun PlayerScreenRuntime.persistIsolatedRewatchSnapshot(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot
) {
    if (rewatchWriteMode != PlayerRewatchWriteMode.REPLAY) return
    val initial = activeRewatchSession ?: return
    val profile = profileId
    val playbackKey = activePlaybackKey
    val episode = playerRewatchEpisode(activeSeasonNumber, activeEpisodeNumber) ?: return
    val metadata = (metaUiState.meta ?: playerMeta)?.takeIf {
        it.id.trim().equals(parentMetaId.trim(), ignoreCase = true)
    }
    val capturedAt = WatchProgressClock.nowEpochMs()
    val completed = snapshot.durationMs > 0L && isWatchProgressComplete(
        positionMs = snapshot.positionMs,
        durationMs = snapshot.durationMs,
        isEnded = snapshot.isEnded
    )
    val position = snapshot.positionMs.coerceAtLeast(0L)
    val duration = snapshot.durationMs.coerceAtLeast(0L)

    val saving: Job = RewatchSaveCoordinator.enqueue {
        try {
            if (ProfileRepository.activeProfileId != profile) return@enqueue
            val current = RewatchSessionRepository.load(profile).firstOrNull {
                it.sessionId == initial.sessionId &&
                    it.ownerKey == initial.ownerKey &&
                    it.canonicalSeriesId.trim().equals(
                        initial.canonicalSeriesId.trim(), ignoreCase = true
                    )
            } ?: return@enqueue
            if (current.episode != episode ||
                current.phase == RewatchPhase.FINISHED ||
                current.phase == RewatchPhase.AWAITING_METADATA
            ) return@enqueue

            val updated = if (completed) {
                val catalog = verifiedPlayerRewatchCompletionCatalog(metadata, episode)
                completeRewatchEpisode(
                    session = current,
                    completedEpisode = episode,
                    orderedPlayableEpisodes = catalog.orderedEpisodes,
                    metadataComplete = catalog.metadataComplete,
                    updatedAtEpochMs = maxOf(capturedAt, current.updatedAtEpochMs)
                )
            } else {
                recordRewatchProgress(
                    session = current,
                    episode = episode,
                    positionMs = position,
                    durationMs = duration,
                    updatedAtEpochMs = maxOf(capturedAt, current.updatedAtEpochMs)
                )
            }
            if (updated == current) return@enqueue
            val accepted = RewatchSessionRepository.upsert(profile, updated)
            if (accepted) {
                notifyCommittedRewatchSave(
                    sink = RewatchIpadCommitHandoff.sink,
                    accepted = true,
                    profileId = profile,
                    activeProfileId = { ProfileRepository.activeProfileId },
                    before = current,
                    after = updated
                )
            }
            if (accepted && ProfileRepository.activeProfileId == profile &&
                rewatchClassifiedKey == playbackKey &&
                activeRewatchSession?.sessionId == updated.sessionId
            ) {
                activeRewatchSession = updated
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A failed replay save must NOT fall through to normal Simkl
            // tracking; the last durable cursor remains authoritative.
            println("Player rewatch: isolated progress write failed: ${error.message}")
        }
    }
    pendingRewatchSaveJob = saving
}


/**
 * Resolves AWAITING_METADATA after the details catalog becomes available,
 * including when a delayed metadata request completes after playback EOF.
 * No provider watched-history endpoint is contacted.
 */
internal suspend fun PlayerScreenRuntime.resolvePendingRewatchMetadata() {
    if (!isIos || rewatchWriteMode != PlayerRewatchWriteMode.REPLAY) return
    val profile = profileId
    val key = activePlaybackKey
    pendingRewatchSaveJob?.join()
    if (ProfileRepository.activeProfileId != profile || key != activePlaybackKey) return

    val session = activeRewatchSession?.takeIf {
        it.phase == RewatchPhase.AWAITING_METADATA &&
            it.lastCompletedEpisode != null
    } ?: return
    val meta = (metaUiState.meta ?: playerMeta)?.takeIf {
        it.id.trim().equals(session.canonicalSeriesId.trim(), ignoreCase = true)
    } ?: return
    val completedEpisode = session.lastCompletedEpisode ?: return
    val catalog = verifiedPlayerRewatchCompletionCatalog(meta, completedEpisode)
    if (!catalog.metadataComplete) return

    val updated = resolvePendingRewatchSuccessor(
        session = session,
        orderedPlayableEpisodes = catalog.orderedEpisodes,
        metadataComplete = true
    )
    if (updated == session || key != activePlaybackKey) return
    val accepted = RewatchSessionRepository.upsert(profile, updated)
    if (accepted) {
        notifyCommittedRewatchSave(
            sink = RewatchIpadCommitHandoff.sink,
            accepted = true,
            profileId = profile,
            activeProfileId = { ProfileRepository.activeProfileId },
            before = session,
            after = updated
        )
    }
    if (accepted && ProfileRepository.activeProfileId == profile &&
        key == activePlaybackKey &&
        activeRewatchSession?.sessionId == updated.sessionId
    ) {
        activeRewatchSession = updated
    }
}
