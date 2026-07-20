(ns brep.spline
  "Portable B-spline and rational NURBS evaluation shared by STEP, IFC and CAD.")

(defn expand-knots
  "Expand unique knots using their multiplicities. When IFC omits knots, create
  a clamped uniform vector for `degree` and `control-count`."
  [knots multiplicities degree control-count]
  (if (and (seq knots) (= (count knots) (count multiplicities)))
    (vec (mapcat (fn [k multiplicity] (repeat multiplicity k)) knots multiplicities))
    (let [interior-count (- control-count degree 1)
          denominator (inc interior-count)]
      (vec (concat (repeat (inc degree) 0.0)
                   (map #(* 1.0 (/ % denominator)) (range 1 (inc interior-count)))
                   (repeat (inc degree) 1.0))))))

(defn parameter-range [degree expanded-knots control-count]
  [(nth expanded-knots degree) (nth expanded-knots control-count)])

(defn basis
  "Return all non-rational Cox–de Boor basis values at `parameter`."
  [degree expanded-knots control-count parameter]
  (let [last-parameter (nth expanded-knots control-count)
        initial (mapv (fn [i]
                        (if (or (and (<= (nth expanded-knots i) parameter)
                                     (< parameter (nth expanded-knots (inc i))))
                                (and (= parameter last-parameter)
                                     (= i (dec control-count))))
                          1.0 0.0))
                      (range control-count))]
    (loop [order 1 values initial]
      (if (> order degree)
        values
        (recur
         (inc order)
         (mapv
          (fn [i]
            (let [left-denominator (- (nth expanded-knots (+ i order))
                                      (nth expanded-knots i))
                  right-denominator (- (nth expanded-knots (+ i order 1))
                                       (nth expanded-knots (inc i)))
                  left (if (zero? left-denominator) 0.0
                           (* (/ (- parameter (nth expanded-knots i)) left-denominator)
                              (nth values i)))
                  right (if (or (zero? right-denominator) (= i (dec control-count))) 0.0
                            (* (/ (- (nth expanded-knots (+ i order 1)) parameter)
                                  right-denominator)
                               (nth values (inc i))))]
              (+ left right)))
          (range control-count)))))))

(defn curve-point
  "Evaluate a rational or non-rational B-spline curve map. The map accepts
  `:degree`, `:control-points`, expanded `:knots`, and optional `:weights`."
  [{:keys [degree control-points knots weights]} parameter]
  (let [control-count (count control-points)
        values (basis degree knots control-count parameter)
        weights (or weights (repeat control-count 1.0))
        coefficients (mapv * values weights)
        denominator (reduce + coefficients)]
    (when (pos? denominator)
      (mapv #(/ % denominator)
            (reduce (fn [sum [coefficient point]]
                      (mapv + sum (mapv #(* coefficient %) point)))
                    (vec (repeat (count (first control-points)) 0.0))
                    (map vector coefficients control-points))))))

(defn surface-point
  "Evaluate a tensor-product rational or non-rational B-spline surface."
  [{:keys [u-degree v-degree control-points u-knots v-knots weights]} u v]
  (let [u-count (count control-points) v-count (count (first control-points))
        u-values (basis u-degree u-knots u-count u)
        v-values (basis v-degree v-knots v-count v)
        weights (or weights (repeat u-count (repeat v-count 1.0)))
        terms (for [i (range u-count) j (range v-count)]
                [(* (nth u-values i) (nth v-values j) (nth (nth weights i) j))
                 (get-in control-points [i j])])
        denominator (reduce + (map first terms))]
    (when (pos? denominator)
      (mapv #(/ % denominator)
            (reduce (fn [sum [coefficient point]]
                      (mapv + sum (mapv #(* coefficient %) point)))
                    (vec (repeat (count (get-in control-points [0 0])) 0.0)) terms)))))
