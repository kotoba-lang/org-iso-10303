(ns brep.kernel
  "BREP (Boundary Representation) topology kernel: vertex, edge, face,
  shell, solid, plus analytic/freeform curve & surface definitions.
  Restored from kami-cad's `brep` module (kami-engine/kami-cad/src/lib.rs,
  deleted PR #82). Points/vectors are `[x y z]` 3-vectors (glam::DVec3 in
  the original) — f64 precision throughout for CAD-grade accuracy."
  (:require [brep.config :as config]))

;; ── vector helpers (DVec3 equivalent) ──

(defn v+ [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn v- [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn v-scale [[x y z] s] [(* x s) (* y s) (* z s)])
(defn v-dot [[ax ay az] [bx by bz]] (+ (* ax bx) (* ay by) (* az bz)))
(defn v-cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn v-length [v] (Math/sqrt (v-dot v v)))
(defn v-normalize [v] (v-scale v (/ 1.0 (v-length v))))
(defn v-distance [a b] (v-length (v- b a)))
(defn v-min [[ax ay az] [bx by bz]] [(min ax bx) (min ay by) (min az bz)])
(defn v-max [[ax ay az] [bx by bz]] [(max ax bx) (max ay by) (max az bz)])

;; ── Surface / Curve ──

(def orientations #{:forward :reversed})

;; Surface variants: {:kind :plane :origin :normal} | {:kind :cylinder ...} | etc.
(defn plane-surface [origin normal] {:kind :plane :origin origin :normal normal})
(defn cylinder-surface [origin axis radius] {:kind :cylinder :origin origin :axis axis :radius radius})
(defn cone-surface [apex axis half-angle] {:kind :cone :apex apex :axis axis :half-angle half-angle})
(defn sphere-surface [center radius] {:kind :sphere :center center :radius radius})
(defn torus-surface [center axis major-radius minor-radius]
  {:kind :torus :center center :axis axis :major-radius major-radius :minor-radius minor-radius})

;; Curve variants
(defn line-curve [origin direction] {:kind :line :origin origin :direction direction})
(defn circle-curve [center normal radius] {:kind :circle :center center :normal normal :radius radius})
(defn ellipse-curve [center normal semi-major semi-minor]
  {:kind :ellipse :center center :normal normal :semi-major semi-major :semi-minor semi-minor})
(defn bspline-curve [degree control-points knots]
  {:kind :bspline-curve :degree degree :control-points control-points :knots knots})

(defn- de-boor-evaluate
  "De Boor evaluation of a B-spline curve at parameter `t`."
  [degree control-points knots t]
  (if (empty? control-points)
    [0.0 0.0 0.0]
    (let [n (count control-points)
          p degree
          cps (vec control-points)
          ks (vec knots)
          t-clamped (max (nth ks p) (min t (nth ks n)))
          k (or (some (fn [i] (when (and (>= t-clamped (nth ks i)) (< t-clamped (nth ks (inc i)))) i))
                      (range p n))
                (if (>= t-clamped (nth ks n)) (dec n) p))
          d0 (mapv (fn [j] (nth cps (+ (- k p) j))) (range (inc p)))]
      (nth
       (reduce
        (fn [d r]
          (reduce
           (fn [d j]
             (let [idx (+ (- k p) j)
                   denom (- (nth ks (+ idx p 1 (- r))) (nth ks idx))]
               (if (< (Math/abs denom) config/epsilon-knot-denominator)
                 d
                 (let [alpha (/ (- t-clamped (nth ks idx)) denom)]
                   (assoc d j (v+ (v-scale (nth d (dec j)) (- 1.0 alpha))
                                  (v-scale (nth d j) alpha)))))))
           d
           (range p (dec r) -1)))
        d0
        (range 1 (inc p)))
       p))))

(defn curve-evaluate
  "Evaluate `curve` at parameter `t`, returning a `[x y z]` point."
  [curve t]
  (case (:kind curve)
    :line (v+ (:origin curve) (v-scale (:direction curve) t))
    :circle (let [n (v-normalize (:normal curve))
                  u (if (< (Math/abs (first n)) 0.9)
                      (v-normalize (v-cross [1.0 0.0 0.0] n))
                      (v-normalize (v-cross [0.0 1.0 0.0] n)))
                  v (v-cross n u)
                  r (:radius curve)]
              (v+ (:center curve) (v+ (v-scale u (* r (Math/cos t))) (v-scale v (* r (Math/sin t))))))
    :ellipse (let [n (v-normalize (:normal curve))
                   u (v-normalize (:semi-major curve))
                   v (v-normalize (v-cross n u))]
               (v+ (:center curve)
                   (v+ (v-scale u (* (v-length (:semi-major curve)) (Math/cos t)))
                       (v-scale v (* (:semi-minor curve) (Math/sin t))))))
    :bspline-curve (de-boor-evaluate (:degree curve) (:control-points curve) (:knots curve) t)))

;; ── BREP topology ──

(defn brep-vertex [id point] {:id id :point point})
(defn brep-edge [id curve start-vertex end-vertex t-range]
  {:id id :curve curve :start-vertex start-vertex :end-vertex end-vertex :t-range t-range})
(defn brep-face [id surface wires orientation] {:id id :surface surface :wires wires :orientation orientation})
(defn brep-shell [id faces orientation] {:id id :faces faces :orientation orientation})
(defn brep-solid [id shells] {:id id :shells shells})

(defn face-count [solid]
  (reduce + 0 (map #(count (:faces %)) (:shells solid))))

(defn edge-count [solid]
  (count (into #{} (mapcat #(mapcat identity (mapcat :wires (:faces %))) (:shells solid)))))

(defn vertex-count
  "Unique vertex IDs referenced by the solid's faces, via the supplied `edges`."
  [solid edges]
  (let [edge-ids (into #{} (mapcat #(mapcat identity (mapcat :wires (:faces %))) (:shells solid)))]
    (count (into #{}
                 (mapcat (fn [e] (when (edge-ids (:id e)) [(:start-vertex e) (:end-vertex e)]))
                         edges)))))

(defn bounding-box
  "Axis-aligned bounding box `[min max]` from wire-edge endpoints + curve
  midpoint samples (an approximation, matches the original)."
  [solid edges vertices]
  (let [vert-map (into {} (map (fn [v] [(:id v) (:point v)]) vertices))
        edge-map (into {} (map (fn [e] [(:id e) e]) edges))
        eids (mapcat #(mapcat identity (mapcat :wires (:faces %))) (:shells solid))]
    (reduce
     (fn [[mn mx] eid]
       (if-let [edge (get edge-map eid)]
         (let [pts (cond-> []
                     (get vert-map (:start-vertex edge)) (conj (get vert-map (:start-vertex edge)))
                     (get vert-map (:end-vertex edge)) (conj (get vert-map (:end-vertex edge))))
               [t0 t1] (:t-range edge)
               mid (curve-evaluate (:curve edge) (* (+ t0 t1) 0.5))
               pts (conj pts mid)]
           (reduce (fn [[mn mx] p] [(v-min mn p) (v-max mx p)]) [mn mx] pts))
         [mn mx]))
     [[##Inf ##Inf ##Inf] [##-Inf ##-Inf ##-Inf]]
     eids)))

(defn make-box
  "Build a rectangular box solid from 8 vertices, 12 edges, 6 faces.
  Returns `[solid edges vertices]`."
  [id [minx miny minz :as vmin] [maxx maxy maxz :as vmax]]
  (let [verts [[minx miny minz] [maxx miny minz] [maxx maxy minz] [minx maxy minz]
               [minx miny maxz] [maxx miny maxz] [maxx maxy maxz] [minx maxy maxz]]
        brep-verts (mapv (fn [i p] (brep-vertex (inc i) p)) (range) verts)
        edge-defs [[0 1] [1 2] [2 3] [3 0] [4 5] [5 6] [6 7] [7 4] [0 4] [1 5] [2 6] [3 7]]
        brep-edges (mapv (fn [i [s e]]
                            (let [sv (nth brep-verts s) ev (nth brep-verts e)
                                  dir (v-normalize (v- (:point ev) (:point sv)))]
                              (brep-edge (+ 100 i) (line-curve (:point sv) dir)
                                         (:id sv) (:id ev) [0.0 (v-distance (:point sv) (:point ev))])))
                          (range) edge-defs)
        face-wires [[[0 1 2 3] [0.0 0.0 -1.0]]
                    [[4 5 6 7] [0.0 0.0 1.0]]
                    [[0 9 4 8] [0.0 -1.0 0.0]]
                    [[2 11 6 10] [0.0 1.0 0.0]]
                    [[3 8 7 11] [-1.0 0.0 0.0]]
                    [[1 10 5 9] [1.0 0.0 0.0]]]
        mid (v-scale (v+ vmin vmax) 0.5)
        faces (mapv (fn [i [eidxs normal]]
                      (let [wire (mapv (fn [ei] (:id (nth brep-edges ei))) eidxs)]
                        (brep-face (+ 200 i) (plane-surface mid normal) [wire] :forward)))
                    (range) face-wires)
        shell (brep-shell 300 faces :forward)]
    [(brep-solid id [shell]) brep-edges brep-verts]))
