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

(deftest boolean-values-use-standard-part21-logical-tokens
  (is (= ".T." (part21/value true)))
  (is (= ".F." (part21/value false)))
  (let [text (part21/file {:schema "IFC4X3_ADD2"}
                          [1 :ifctriangulatedfaceset [:ref 2] :$ true
                           [:list [:list 1 2 3]] :$])
        parsed (part21/parse-file text)]
    (is (string/includes? text ", .T.,"))
    (is (true? (get-in parsed [:part21/entity-by-id 1 :args 2])))))

(deftest preserves-integer-and-real-lexical-types
  (is (= "3" (part21/value 3)))
  (is (= "3." (part21/value 3.0))))

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

(deftest decodes-standard-extended-unicode-strings
  (is (= "♫Don'tÄrgerhôtelЊет"
         (part21/parse-value
          "'\\X2\\266B\\X0\\Don''t\\X2\\00C4\\X0\\rgerh\\X2\\00F4\\X0\\tel\\X2\\040A04350442\\X0\\'")))
  (is (= "😀" (part21/parse-value "'\\X4\\0001F600\\X0\\'"))))

(deftest reads-commented-exchange-files-without-losing-following-entities
  (let [text (str "ISO-10303-21;\nHEADER;\nFILE_SCHEMA(('IFC4'));\nENDSEC;\nDATA;\n"
                  "/* project definition */\n"
                  "#1=IFCPROJECT('gid',$,'Project /* literal */',$,$,$,$,(),$);\n"
                  "#2=IFCSIUNIT(*,.LENGTHUNIT.,$,.METRE.); /* inline comment */\n"
                  "/* comment between tokens */ #3=IFCCOLUMN('column',$,'Column',$,$,$,$,$,$);\n"
                  "ENDSEC;\nEND-ISO-10303-21;\n")
        parsed (part21/parse-file text)]
    (is (= [1 2 3] (mapv :id (:part21/entities parsed))))
    (is (= "Project /* literal */"
           (get-in parsed [:part21/entity-by-id 1 :args 2])))
    (is (= :* (get-in parsed [:part21/entity-by-id 2 :args 0])))
    (is (= :ifccolumn (get-in parsed [:part21/entity-by-id 3 :type])))))
