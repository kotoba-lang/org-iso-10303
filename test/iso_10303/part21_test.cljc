(ns iso-10303.part21-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [iso-10303.part21 :as part21]))

(deftest generic-part21
  (is (= "#1 = X(IFCTEXT('a''b'), #2);"
         (part21/entity [1 :x [:typed :ifctext "a'b"] [:ref 2]])))
  (is (string/includes?
       (part21/file {:schema "IFC4X3_ADD2"} [1 :ifcproject "p"])
       "FILE_SCHEMA(('IFC4X3_ADD2'))")))
