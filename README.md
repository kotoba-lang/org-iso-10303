# kotoba-lang/brep

Zero-dep portable `.cljc` — restored from the legacy `kami-engine/kami-cad`
Rust crate (deleted in kotoba-lang/kami-engine PR #82 "Remove Rust workspace
from kami-engine") as part of the **clj-wgsl migration** (ADR-2607010930,
`com-junkawasaki/root`).

KAMI CAD kernel: BREP solid modeling, parametric feature tree, assembly
management, tessellation.

**Named `brep`, not `cad`** — `kotoba-lang/cad` already exists as an
unrelated, actively-developed industrial CAD/CAM workbench (artifact
registry + policy-gated runner + coverage scoring, GitHub Pages site). This
repo restores the legacy `kami-cad` BREP kernel under a collision-free name.

| Namespace | Restored from | Purpose |
|---|---|---|
| `brep.kernel` | `brep` | BREP topology (vertex/edge/face/shell/solid) + analytic/freeform curve & surface definitions |
| `brep.feature` | `feature` | Parametric feature tree (sketch/extrude/revolve/fillet/chamfer/sweep/loft/shell/pattern/boolean) |
| `brep.assembly` | `assembly` | Part instances, assembly constraints, BOM extraction |
| `brep.tessellate` | `tessellate` | BREP solid -> triangle mesh, volume/surface-area estimates |
| `brep.config` | (new) | EDN-authority tessellation LOD + epsilon constants (`resources/brep/tessellation.edn`), consumed by `brep.kernel`/`brep.tessellate` in place of inline magic numbers |

f64 precision throughout (`[x y z]` 3-vectors, glam::DVec3 in the
original) for CAD-grade accuracy. Depends on `kotoba-lang/engineer` for
shared contracts (sketch constraint kind vocabulary mirrors
`engineer.constraint/kinds`).

## Status

Restored — all 4 modules ported from the original 1194-line Rust `lib.rs`,
with all 9 original Rust unit tests mirrored 1:1 in `test/brep_test.cljc`
(+1 smoke test), plus additional coverage for `brep.kernel` vector math,
curve evaluation, feature-tree edge cases, and the `brep.config` EDN
authority sync check that weren't exercised by the original Rust test
suite, plus real (beyond-original-scope) `make-cylinder` + `:revolve`
coverage — 30 tests / 386 assertions, 0 failures. Pure data + pure
functions throughout; no IO/GPU.

`brep.kernel/make-cylinder` (new, beyond the original Rust scope) builds a
real cylindrical solid — two N-segment polygonal cap faces + one
`cylinder-surface` wall, `brep.tessellate` and `brep.kernel/bounding-box`
both handle it correctly (validated against the analytic πr²h / 2πr²+2πrh
formulas within ~2%, and against exact bounding-box radius/height).
`cylinder-surface` now optionally carries an explicit `:height` — fixes a
real bug where `brep.tessellate`'s cylinder wall always used
`brep.config/cylinder-default-height` (a global constant) regardless of
the solid's actual extent.

Tessellation LOD constants (cylinder/sphere segment counts) and epsilon
thresholds (point-merge, knot-denominator) live in `brep.config` — a
portable inline mirror of the EDN authority at
`resources/brep/tessellation.edn` — rather than as magic numbers inline
in `brep.kernel`/`brep.tessellate`. `brep.config-loader` (JVM-only) and
the `edn-matches-resource` test keep the two in sync.

`brep.tessellate` was split out from `brep.kernel` to avoid a circular
dependency the original Rust crate had (`brep.rs`'s `volume`/
`surface_area` called `super::tessellate::tessellate_solid` from within
the same crate — fine for a single Rust crate's internal module graph, but
Clojure namespaces can't have cycles). Here `brep.kernel` is pure topology
+ vector math; `brep.tessellate` depends on it and owns tessellation +
the volume/surface-area estimates that need a tessellated mesh as input.

Feature-tree evaluation started at the original's scope (only `:extrude`
with `:operation :new`, a box-like prism from a unit-square
cross-section) and now also handles `:revolve` — but only for a full 2π
turn of a single axis-parallel sketch-line profile (constant radius,
`make-cylinder`); an angled-line profile (cone/frustum) or a
partial-angle revolve returns `:error`, not a silently wrong shape (no
cone tessellation exists yet to render one correctly). fillet/chamfer/
full-boolean/sweep/loft/shell/pattern remain not-yet-implemented (matches
the original's `log::warn!` TODOs — a general boolean CSG kernel needs
robust polygon/polyhedron clipping, which is correctness-critical enough
not to rush).

## Develop

```bash
clojure -M:test
clojure -M:lint
```
