# Map: Architecture Deepening and Seam Purification (Post-v3.3)

## Overview
Post-v3.3 architectural deepening and seam purification across four key friction points:
1. Pruning unused geometry interpolation math in `MorphingPlayerMath` and ensuring GPU draw phase container morphing.
2. Encapsulating contextual selection state and batch queueing in `DirectoryBrowserViewModel` and eliminating direct session leakage.
3. Unifying track row subtitle formatting and missing-tag fallbacks across list rows to strictly enforce 72dp height parity.
4. Consolidating the duplicate track reconciliation, deduplication, and cover self-healing pipeline in `PlaylistRepositoryImpl`.

## Issues Graph
- [ ] **01-prune-unused-math-and-gpu-morphing-container** (Ready)
- [ ] **02-deepen-contextual-selection-and-batch-dispatch** (Ready)
- [ ] **03-unify-track-row-subtitles-and-fallback** (Ready)
- [ ] **04-consolidate-playlist-track-reconciliation** (Ready)

## Decisions So Far
- Initial scaffolding and worktree isolation established on `feat/architecture-deepening-and-seam-purification`.
