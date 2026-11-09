# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root: the authoritative source of current ubiquitous language (totally devoid of implementation details; consult for pure business domain entities and strict `_Avoid_` anti-patterns).
- **`CODING_STANDARDS.md`** at the repo root: the authoritative source of current architectural invariants and engineering benchmarks (takes absolute precedence over all historical `docs/adr/` entries).
- **`docs/adr/`**: read active ADRs that touch the area you're about to work in.
  - **Skip superseded ADRs**: If an ADR has `Superseded by ADR NNNN` under its title, skip it and read the superseding ADR instead (e.g., `ADR-0007` is superseded by `ADR-0028` and `ADR-0031`; `ADR-0011` is superseded by `ADR-0025`; `ADR-0014` and `ADR-0021` are superseded by `ADR-0034`; `ADR-0024` is superseded by `ADR-0036`).
  - **Respect retroactive amendments**: `ADR-0034` (CUE virtual track support completely purged), `ADR-0035` (dual-track `browsingServer` vs. `activeServer` decoupling), and `ADR-0036` (MediaLibraryRepository consolidation) retroactively amend earlier ADRs (specifically `ADR-0008`, `ADR-0010`, `ADR-0015`, `ADR-0016`, `ADR-0017`, `ADR-0018`, `ADR-0019`, `ADR-0020`, `ADR-0022`, `ADR-0023`, `ADR-0025`, `ADR-0026`, `ADR-0028`, `ADR-0030`, `ADR-0032b`, and `ADR-0033`). Ignore any CUE, hard foreign key cascades, or single-track server-coupling clauses in older ADRs. (Note: `0032` is disambiguated into `0032a` for palette theming and `0032b` for local SAF storage; the next new ADR number is `0037`.)

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `domain-modeling` skill creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo:

```text
/
├── GLOSSARY.md
├── CODING_STANDARDS.md
├── docs/adr/
│   ├── 0001-use-androidx-media3-with-ffmpeg-extension.md
│   └── ...
└── app/src/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `domain-modeling`).

## Flag ADR conflicts

If your output contradicts an active (non-superseded) ADR or `CODING_STANDARDS.md`, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0002 (pure online streaming without persistent disk cache), but worth reopening because…_
