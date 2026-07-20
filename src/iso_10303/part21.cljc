(ns iso-10303.part21
  "Generic ISO 10303-21 physical-file serialization shared by STEP and IFC."
  (:require [clojure.string :as string]))

(defn- token [value]
  (string/upper-case (string/replace (name value) "-" "_")))

(defn- step-real [value]
  (let [s (str (double value))]
    (if (string/ends-with? s ".0") (subs s 0 (dec (count s))) s)))

(declare value)
(defn value [v]
  (cond
    (string? v) (str "'" (string/replace v "'" "''") "'")
    (and (vector? v) (= :typed (first v)))
    (str (token (second v)) "(" (value (nth v 2)) ")")
    (and (vector? v) (= :ref (first v))) (str "#" (second v))
    (and (vector? v) (= :list (first v)))
    (str "(" (string/join ", " (map value (rest v))) ")")
    (= :$ v) "$"
    (= :* v) "*"
    (keyword? v) (str "." (token v) ".")
    (number? v) (step-real v)
    :else (str v)))

(defn entity [[id entity-type & args]]
  (str "#" id " = " (token entity-type) "("
       (string/join ", " (map value args)) ");"))

(defn file
  [{:keys [description name schema author org] :or {schema "AUTOMOTIVE_DESIGN"}}
   & entities]
  (str "ISO-10303-21;\nHEADER;\n"
       "FILE_DESCRIPTION(('" (or description "") "'), '2;1');\n"
       "FILE_NAME('" (or name "") "', '', ('" (or author "") "'), ('"
       (or org "") "'), '', '', '');\n"
       "FILE_SCHEMA(('" schema "'));\nENDSEC;\nDATA;\n"
       (string/join "\n" (map entity entities))
       "\nENDSEC;\nEND-ISO-10303-21;\n"))
