(ns brep-test
  "Restoration-fidelity tests — one per original kami-cad Rust test
  (kami-engine/kami-cad/src/lib.rs `mod tests`, deleted PR #82) — plus
  additional coverage for `brep.kernel` vector math, curve evaluation,
  feature-tree edge cases, and the `brep.config` EDN authority that
  weren't exercised by the original Rust test suite."
  (:require [clojure.test :refer [deftest is testing]]
            [brep]
            [brep.config :as config]
            #?(:clj [brep.config-loader :as loader])
            [brep.kernel :as k]
            [brep.feature :as feature]
            [brep.assembly :as assembly]
            [brep.tessellate :as tessellate]))

(defn- close? [a b eps] (< (Math/abs (- a b)) eps))

(deftest namespace-loads
  (testing "the restored CLJC namespace loads"
    ;; `the-ns` is JVM-only (no cljs equivalent reachable from user code);
    ;; the whole form must be behind the reader conditional (not just a
    ;; spliced :require) or clj-kondo's cljs analysis pass flags `the-ns`
    ;; as an unresolved symbol.
    (is (some? #?(:clj (the-ns 'brep) :cljs true)))))

;; mirrors `create_box_solid`
(deftest create-box-solid
  (let [[solid edges verts] (k/make-box 1 [0.0 0.0 0.0] [10.0 20.0 30.0])]
    (is (= 6 (k/face-count solid)))
    (is (= 12 (k/edge-count solid)))
    (is (= 8 (k/vertex-count solid edges)))
    (is (= 8 (count verts)))))

;; mirrors `bounding_box_accuracy`
(deftest bounding-box-accuracy
  (let [vmin [-5.0 -5.0 -5.0] vmax [5.0 5.0 5.0]
        [solid edges verts] (k/make-box 1 vmin vmax)
        [bb-min bb-max] (k/bounding-box solid edges verts)]
    (is (< (Math/abs (- (first bb-min) -5.0)) 1e-10))
    (is (< (Math/abs (- (nth bb-max 2) 5.0)) 1e-10))))

;; mirrors `feature_tree_extrude_produces_solid`
(deftest feature-tree-extrude-produces-solid
  (let [tree (-> (feature/feature-tree)
                  (feature/add-feature
                   (feature/sketch-feature 1 (feature/sketch-plane-xy)
                                            [(feature/sketch-circle [0.0 0.0] 5.0)]))
                  (feature/add-feature
                   (feature/extrude-feature 2 1 [0.0 0.0 1.0] 10.0 :new)))]
    (is (= 2 (feature/tree-len tree)))
    (let [[status [solid _edges _verts]] (feature/evaluate tree)]
      (is (= :ok status))
      (is (= 6 (k/face-count solid))))))

;; mirrors `feature_suppress_unsuppress`
(deftest feature-suppress-unsuppress
  (let [tree (-> (feature/feature-tree)
                  (feature/add-feature (feature/extrude-feature 1 0 [0.0 0.0 1.0] 5.0 :new))
                  (feature/add-feature (feature/fillet-feature 2 [100] 1.0))
                  (feature/suppress 2))
        [status _] (feature/evaluate tree)]
    (is (= :ok status))
    (let [tree (feature/unsuppress tree 2)]
      (is (= 2 (feature/tree-len tree))))))

;; mirrors `assembly_bom`
(deftest assembly-bom
  (let [bolt (assembly/part-ref 1 "M6_bolt")
        nut (assembly/part-ref 2 "M6_nut")
        [_ asm] (-> (assembly/assembly "test_assembly")
                     (assembly/add-instance bolt (assembly/affine3-identity) "bolt_1"))
        [_ asm] (assembly/add-instance asm bolt (assembly/affine3-identity) "bolt_2")
        [_ asm] (assembly/add-instance asm nut (assembly/affine3-identity) "nut_1")
        [_ asm] (assembly/add-instance asm nut (assembly/affine3-identity) "nut_2")
        [_ asm] (assembly/add-instance asm bolt (assembly/affine3-identity) "bolt_3")
        bom (assembly/get-bom asm)]
    (is (= 2 (count bom)))
    (let [bolt-entry (first (filter #(= (:part-name %) "M6_bolt") bom))
          nut-entry (first (filter #(= (:part-name %) "M6_nut") bom))]
      (is (= 3 (:quantity bolt-entry)))
      (is (= 2 (:quantity nut-entry))))))

;; mirrors `tessellation_produces_vertices_and_indices`
(deftest tessellation-produces-vertices-and-indices
  (let [[solid edges verts] (k/make-box 1 [0.0 0.0 0.0] [1.0 1.0 1.0])
        [positions indices] (tessellate/tessellate-solid solid edges verts)]
    (is (seq positions))
    (is (seq indices))
    (doseq [idx indices] (is (< idx (count positions))))
    (is (= 0 (mod (count indices) 3)))))

;; mirrors `surface_area_unit_cube`
(deftest surface-area-unit-cube
  (let [[solid edges verts] (k/make-box 1 [0.0 0.0 0.0] [1.0 1.0 1.0])
        area (tessellate/surface-area solid edges verts)]
    (is (< (Math/abs (- area 6.0)) 0.5))))

;; mirrors `volume_positive_for_solid`
(deftest volume-positive-for-solid
  (let [[solid edges verts] (k/make-box 1 [0.0 0.0 0.0] [2.0 3.0 4.0])
        vol (tessellate/volume solid edges verts)]
    (is (> vol 0.0))))

;; mirrors `assembly_constraint_solve_validates_instances`
(deftest assembly-constraint-solve-validates-instances
  (let [part (assembly/part-ref 1 "plate")
        [id-a asm] (-> (assembly/assembly "constrained")
                        (assembly/add-instance part (assembly/affine3-identity) "plate_a"))
        [id-b asm] (assembly/add-instance asm part (assembly/affine3-identity) "plate_b")
        asm (assembly/add-constraint asm (assembly/mate-constraint id-a 200 id-b 201))
        [status _] (assembly/solve asm)]
    (is (= :ok status))
    (let [asm (assembly/add-constraint asm (assembly/distance-constraint id-a 200 999 201 5.0))
          [status _] (assembly/solve asm)]
      (is (= :error status)))))

;; ── brep.kernel vector math (previously only exercised indirectly via
;; make-box / bounding-box; not covered by the original Rust test suite,
;; which had no standalone DVec3 unit tests) ──

(deftest vec3-add-sub-scale
  (is (= [4.0 6.0 8.0] (k/v+ [1.0 2.0 3.0] [3.0 4.0 5.0])))
  (is (= [1.0 1.0 1.0] (k/v- [4.0 5.0 6.0] [3.0 4.0 5.0])))
  (is (= [2.0 4.0 6.0] (k/v-scale [1.0 2.0 3.0] 2.0))))

(deftest vec3-dot-cross
  (is (= 32.0 (k/v-dot [1.0 2.0 3.0] [4.0 5.0 6.0])))
  (is (= [0.0 0.0 1.0] (k/v-cross [1.0 0.0 0.0] [0.0 1.0 0.0]))))

(deftest vec3-length-normalize-distance
  (is (= 5.0 (k/v-length [3.0 4.0 0.0])))
  (is (= [1.0 0.0 0.0] (k/v-normalize [5.0 0.0 0.0])))
  (is (= 5.0 (k/v-distance [0.0 0.0 0.0] [3.0 4.0 0.0]))))

(deftest vec3-min-max
  (is (= [1.0 1.0 1.0] (k/v-min [1.0 5.0 1.0] [5.0 1.0 5.0])))
  (is (= [5.0 5.0 5.0] (k/v-max [1.0 5.0 1.0] [5.0 1.0 5.0]))))

;; ── brep.kernel curve evaluation (not covered by the original Rust test
;; suite or the initial CLJC restoration's test file) ──

(deftest curve-evaluate-line
  (is (= [5.0 0.0 0.0] (k/curve-evaluate (k/line-curve [0.0 0.0 0.0] [1.0 0.0 0.0]) 5.0))))

(deftest curve-evaluate-circle-stays-on-radius
  (testing "every evaluated point of a circle curve is `radius` from `center` (invariant, independent of frame choice)"
    (let [curve (k/circle-curve [1.0 2.0 3.0] [0.0 0.0 1.0] 2.0)]
      (doseq [t [0.0 (/ Math/PI 4) (/ Math/PI 2) Math/PI (* 1.5 Math/PI)]]
        (let [p (k/curve-evaluate curve t)]
          (is (close? (k/v-distance p [1.0 2.0 3.0]) 2.0 1e-9)))))))

(deftest curve-evaluate-bspline-linear-degree1
  (testing "degree-1 clamped bspline reduces to linear interpolation between the two control points"
    (let [curve (k/bspline-curve 1 [[0.0 0.0 0.0] [10.0 0.0 0.0]] [0.0 0.0 1.0 1.0])
          p (k/curve-evaluate curve 0.5)]
      (is (close? (nth p 0) 5.0 1e-9)))))

;; ── brep.feature edge cases (not covered by the original Rust test
;; suite's happy-path tests) ──

(deftest feature-evaluate-empty-tree-errors
  (let [[status _] (feature/evaluate (feature/feature-tree))]
    (is (= :error status))))

(deftest sketch-plane-normal-and-origin
  (is (= [0.0 0.0 1.0] (feature/sketch-plane-normal (feature/sketch-plane-xy))))
  (is (= [0.0 1.0 0.0] (feature/sketch-plane-normal (feature/sketch-plane-xz))))
  (is (= [1.0 0.0 0.0] (feature/sketch-plane-normal (feature/sketch-plane-yz))))
  (is (= [0.0 0.0 0.0] (feature/sketch-plane-origin (feature/sketch-plane-xy))))
  (let [custom (feature/sketch-plane-custom [1.0 1.0 1.0] [0.0 5.0 0.0])]
    (is (= [1.0 1.0 1.0] (feature/sketch-plane-origin custom)))
    (is (= [0.0 1.0 0.0] (feature/sketch-plane-normal custom)))))

;; ── brep.assembly edge cases (not covered by the original Rust test
;; suite's happy-path tests) ──

(deftest active-count-excludes-suppressed
  (let [part (assembly/part-ref 1 "washer")
        [id1 asm] (-> (assembly/assembly "a")
                      (assembly/add-instance part (assembly/affine3-identity) "w1"))
        [_id2 asm] (assembly/add-instance asm part (assembly/affine3-identity) "w2")]
    (is (= 2 (assembly/active-count asm)))
    (let [asm (update asm :instances
                       (fn [is] (mapv (fn [i] (if (= (:id i) id1) (assoc i :suppressed true) i)) is)))]
      (is (= 1 (assembly/active-count asm))))))

;; ── brep.config EDN authority (config.cljc is the portable inline
;; mirror; resources/brep/tessellation.edn is the human-editable
;; authority; the two must stay byte-for-byte in sync) ──

#?(:clj
   (deftest edn-matches-resource
     (is (= (loader/load-edn-resource loader/resource-path) config/config)
         "resources/brep/tessellation.edn must stay byte-for-byte in sync with brep.config/config")))

(deftest config-values-loaded
  (is (= 24 config/cylinder-segments))
  (is (= 16 config/sphere-u-segments))
  (is (= 12 config/sphere-v-segments)))
