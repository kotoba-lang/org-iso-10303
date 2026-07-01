(ns brep-test
  "Restoration-fidelity tests — one per original kami-cad Rust test
  (kami-engine/kami-cad/src/lib.rs `mod tests`, deleted PR #82)."
  (:require [clojure.test :refer [deftest is testing]]
            [brep]
            [brep.kernel :as k]
            [brep.feature :as feature]
            [brep.assembly :as assembly]
            [brep.tessellate :as tessellate]))

(deftest namespace-loads
  (testing "the restored CLJC namespace loads"
    (is (some? (the-ns 'brep)))))

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
