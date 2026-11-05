# Map: Architecture Deepening and Seam Purification (Post-v3.3)

## Overview
Post-v3.3 architectural deepening and seam purification across four key friction points:
1. Pruning unused geometry interpolation math in `MorphingPlayerMath` and ensuring GPU draw phase container morphing.
2. Encapsulating contextual selection state and batch queueing in `DirectoryBrowserViewModel` and eliminating direct session leakage.
3. Unifying track row subtitle formatting and missing-tag fallbacks across list rows to strictly enforce 72dp height parity.
4. Consolidating the duplicate track reconciliation, deduplication, and cover self-healing pipeline in `PlaylistRepositoryImpl`.

## Issues Graph
- [x] **01-prune-unused-math-and-gpu-morphing-container** (Resolved)
- [x] **02-deepen-contextual-selection-and-batch-dispatch** (Resolved)
- [ ] **03-unify-track-row-subtitles-and-fallback** (Ready)
- [ ] **04-consolidate-playlist-track-reconciliation** (Ready)

## Decisions So Far
- Initial scaffolding and worktree isolation established on `feat/architecture-deepening-and-seam-purification`.
- **Decision 1 (Ticket 01 Morphing Container Draw-Phase & Math Pruning)**: Pruned 8 dead math functions from `MorphingPlayerMath`, internalized fade thresholds into `MorphingPlayerDefaults`, moved container dynamic sizing/clipping animations to GPU Draw Phase (`graphicsLayer` / `drawBehind`), and updated `PlayerSheetExpansionTest` (14/14 passed).
- **Decision 2 (Ticket 02 Contextual Selection Encapsulation)**: Lifted contextual file selection state machine into `DirectoryBrowserViewModel`, encapsulated `musicPlayerAppSession` as private, eliminated direct UI session penetration, and added 7 unit test cases in `DirectoryBrowserViewModelTest` (60/60 passed).

