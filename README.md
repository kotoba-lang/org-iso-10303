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

f64 precision throughout (`[x y z]` 3-vectors, glam::DVec3 in the
original) for CAD-grade accuracy. Depends on `kotoba-lang/engineer` for
shared contracts (sketch constraint kind vocabulary mirrors
`engineer.constraint/kinds`).

## Status

Restored — all 4 modules ported from the original 1194-line Rust `lib.rs`,
with all 9 original Rust unit tests mirrored 1:1 in `test/brep_test.cljc`
(+1 smoke test) — 10 tests / 58 assertions, 0 failures. Pure data + pure
functions throughout; no IO/GPU.

`brep.tessellate` was split out from `brep.kernel` to avoid a circular
dependency the original Rust crate had (`brep.rs`'s `volume`/
`surface_area` called `super::tessellate::tessellate_solid` from within
the same crate — fine for a single Rust crate's internal module graph, but
Clojure namespaces can't have cycles). Here `brep.kernel` is pure topology
+ vector math; `brep.tessellate` depends on it and owns tessellation +
the volume/surface-area estimates that need a tessellated mesh as input.

Feature-tree evaluation matches the original's scope: only `:extrude`
with `:operation :new` produces a solid (a box-like prism from a
unit-square cross-section); revolve/fillet/chamfer/full-boolean are
documented as not-yet-implemented (matches the original's `log::warn!`
TODOs — full BREP boolean requires a proper boolean kernel).

## Develop

```bash
clojure -M:test
```
