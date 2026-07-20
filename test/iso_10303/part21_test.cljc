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

(deftest reads-part21-entities
  (let [text (str "ISO-10303-21;\nHEADER;\nFILE_SCHEMA(('IFC4X3_ADD2'));\nENDSEC;\nDATA;\n"
                  "#1=IFCPROJECT('2O2Fr$t4X7Zf8NOew3FLOH',$,'O''Brien Tower',$,$,$,$,$,$);\n"
                  "#2=IFCAXIS2PLACEMENT3D(#3,$,$);\n"
                  "#3=IFCCARTESIANPOINT((0.,1.5,2.));\nENDSEC;\nEND-ISO-10303-21;\n")
        parsed (part21/parse-file text)]
    (is (= "IFC4X3_ADD2" (:part21/schema parsed)))
    (is (= "O'Brien Tower" (get-in parsed [:part21/entity-by-id 1 :args 2])))
    (is (= [:ref 3] (get-in parsed [:part21/entity-by-id 2 :args 0])))
    (is (= [:list 0.0 1.5 2.0] (get-in parsed [:part21/entity-by-id 3 :args 0])))))
