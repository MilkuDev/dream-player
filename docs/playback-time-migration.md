Phase 1 — Architecture Refactor

☐ Introduce PlaybackTimeSnapshot
☐ Introduce PlaybackTimeSource interface
☐ Implement AndroidPlaybackTimeSource (Media3)
☐ Integrate PlaybackTimeSource into AudioPlayer
☐ Remove playbackProgressMs from PlaybackUiState
☐ Remove ViewModel-owned playback timeline (startProgressUpdates, related state updates)
☐ Migrate SavePoints to PlaybackTimeSource
☐ Verify project compiles

Phase 2 — UI Migration

☐ Migrate PlayerProgress to PlaybackTimeSource
☐ Remove interpolation
☐ Remove drift compensation
☐ Remove anchor timeline
☐ Remove obsolete playback-time infrastructure
☐ Verify project compiles
☐ Perform manual playback testing