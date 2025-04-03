(ns modex.mcp.tools-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [modex.mcp.tools :as tools]
            [jsonista.core :as json]))

(deftest tool-macro-tests

  (testing "tools/handler returns fn with :mcp metadata"
    ; todo, infer :required via :or.
    (let [handler (tools/handler
                    [{:keys [title age]
                      :or   {age 37}
                      :type {title :string}
                      :doc  {title "Person's name"}}]
                    (str "Hi, " title " (" age ")"))]
      (is (= '{:mcp {:type {title :string}
                     :doc  {title "Person's name"}}})
          (meta handler))
      (is (= "Hi, Petrus (37)" (handler {:title "Petrus"}))))

    (testing "tools/handler validates :type"
      (is (thrown? AssertionError (tools/handler [{:keys [a b]
                                                   :type {a :text}}]))))))

(deftest tools-tests
  (testing "Tool argument types :text are correctly coerced to JSON"
    (json/write-value-as-string [{:x {:y [:z "hi"]}} :a :b] json/keyword-keys-object-mapper)
    (let [tool (tools/map->Tool
                 {:name :greet
                  :doc "Greeter"
                  :args [(tools/map->Parameter {:name :name
                                                :doc "A person's name"
                                                :type :text
                                                :required true})]})]
      (is (= {:name        :greet
              :description "Greeter"
              :inputSchema {:type "object"
                            :required [:name]
                            :properties {:name {:type :text
                                                :doc "A person's name"
                                                :required true}}}}
             (tools/tool->json-schema tool)))))

  (testing "we can make a tool"
    (let [adder (tools/tool (add [{:keys [x y]
                                   :type {x :number
                                          y :number}}]
                                 [(+ x y)]))]
      (testing "and we can invoke that tool with an argument map."
        (is (= {:success true :results [13]} (tools/invoke-tool adder {:x 6 :y 7}))))))

  (testing "tools macro just calls tool for each tool definition and returns a map from (keyword tool-name) => tool."
    (let [my-tools (tools/tools
                     (add "Adds two numbers."
                       [{:keys [x y]
                         :type {x :number
                                y :number}}]
                       [(+ x y)])
                     (subtract "Subtracts two numbers (b from a)"
                       [^{:type :number} a,
                        ^{:type :number} b] [(- a b)]))]
      (testing "can invoke tool"
        (let [{:keys [add subtract]} my-tools]
          (is (= {:success true, :results [11]} (tools/invoke-tool add {:x 5, :y 6})))
          (is (= {:success true, :results [3]} (tools/invoke-tool subtract {:a 10 :b 7})))))))

  (testing "docstrings are optional (defaults to tool name)")
  (let [add-tool (tools/tool
                   (add [^{:type :number} x
                         ^{:type :number} y]
                        (+ x y)))]
    (is (= ({:name :add
             :doc  "add"
             :args [(tools/map->Parameter {:name :x
                                           :doc "x"
                                           :type :number
                                           :required true})
                    (tools/map->Parameter {:name     :y
                                           :doc      "y"
                                           :type     :number
                                           :required true})]}
            (dissoc add-tool :handler)))))

  (testing "we can define multiple tools"
    (let [multiple-tools
          (tools/tools
            (greet [^{:type :string} name
                    ^{:type :number} birth-year]
                   (let [current-year (+ 1900 (.getYear (java.util.Date.)))] ; => 2025])
                     (str "Hello, " name "! You are " (- current-year birth-year) " years old :)")))
            (add [^{:type :number} a
                  ^{:type :number} b]
                 (+ a b))
            (subtract "Subtracts two numbers"
                      [{:keys [x y]
                        :type {x :number
                               y :number}
                        :or {y 0}}]
                      (+ x y)))]

      (is (= [{:name :greet
               :doc  "greet"
               :args [(tools/map->Parameter {:name :name, :doc "name", :type :string, :required true})
                      (tools/map->Parameter {:name :birth-year, :doc "birth-year", :type :number, :required true})]}
              {:name :add
               :doc  "add"
               :args [(tools/map->Parameter {:name :a, :doc "a", :type :number, :required true})
                      (tools/map->Parameter {:name :b, :doc "b", :type :number, :required true})]}
              {:name :subtract
               :doc  "Subtracts two numbers"
               :args [(tools/map->Parameter {:name :x, :doc "x", :type :number, :required true})
                      (tools/map->Parameter {:name :y, :doc "y", :type :number, :required false :default 0})]}]
             (map #(dissoc % :handler) (vals multiple-tools))))))

  (testing "tools can dispatch to external handlers works"
    (let [add-handler         (fn [a b] (+ a b))
          tools-with-handlers (tools/tools
                                (add "" [^{:type :number} a
                                         ^{:type :number} b]
                                  [(add-handler a b)])
                                (subtract "subtracts two numbers"
                                  [^{:type :number} x
                                   ^{:type :number} y]
                                  [(- x y)]))
          add-tool (get tools-with-handlers :add)
          subtract-tool (get tools-with-handlers :subtract)]
      (is (= {:success true :results [11]} (tools/invoke-tool add-tool {:a 5 :b 6})))
      (is (= {:success true :results [4]} (tools/invoke-tool subtract-tool {:x 10 :y 6}))))))
