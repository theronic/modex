(ns modex.mcp.tools-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [modex.mcp.tools :as tools]
            [jsonista.core :as json]))

(deftest tools-tests

  (testing "Tool argument types :text are correctly coerced to JSON"
    (let [tool (tools/map->Tool
                 {:name :greet
                  :doc "Greeter"
                  :args [(tools/->Parameter :name "A person's name" :text true)]})]
      ;(is (= nil (tools/input-schema tool)))
      ;(is (= nil (tools/tool->json-schema tool)))
      ;(is (= nil (json/write-value-as-string (tools/tool->json-schema tool) json/keyword-keys-object-mapper)))
      (is (= "{\"name\":\"greet\",\"description\":\"Greeter\",\"inputSchema\":{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"text\",\"doc\":\"A person's name\"}}}}"
             (json/write-value-as-string (tools/tool->json-schema tool) json/keyword-keys-object-mapper)))))
    ;(is (= {:type :text :doc "A person's name"}) (tools/tool-arg->property (tools/->Parameter :name "A person's name" :text true)))
    ;(is (= {:type :text :doc "A person's name"}) (tools/tool-args->input-schema [(tools/->Parameter :name "A person's name" :text true)])))

  (testing "we can make a tool"
    (let [adder (tools/tool (add [x y] (+ x y)))]
      (testing "and we can invoke that tool with an argument map."
        (is (= [13 nil] (tools/invoke-tool adder {:x 6 :y 7}))))))

  (testing "tools macro just calls tool for each tool definition and returns a map from (keyword tool-name) => tool."
    (let [my-tools (tools/tools
                     (add "Adds two numbers." [x y] (+ x y))
                     (subtract "Subtracts two numbers (b from a)" [a b] (- a b)))]
      (testing "can invoke tool"
        (let [{:keys [add subtract]} my-tools]
          (is (= [11 nil] (tools/invoke-tool add {:x 5, :y 6})))
          (is (= [3 nil] (tools/invoke-tool subtract {:a 10 :b 7})))))))

  (testing "docstrings are optional (defaults to tool name)")
  (let [add-tool (tools/tool
                   (add [^{:type :number} x
                         ^{:type :number} y]
                        (+ x y)))]
    (is (= ({:name :add
             :doc  "add"
             :args [(tools/->Parameter :x "x" :number true)
                    (tools/->Parameter :y "y" :number true)]}
            (dissoc add-tool :handler)))))

  (testing "we can define multiple tools"
    (let [multiple-tools (tools/tools
                           (greet [^{:type :string} name
                                   ^{:type :number} birth-year]
                                  (let [current-year (+ 1900 (.getYear (java.util.Date.)))] ; => 2025])
                                    (str "Hello, " name "! You are " (- current-year birth-year) " years old :)")))
                           (add [^{:type :number} a
                                 ^{:type :number} b]
                                (+ a b))
                           (subtract [^{:type :number} x
                                      ^{:type :number} y]
                                     (+ x y)))]

      (is (= [{:name :greet,
               :doc  "greet",
               :args [(tools/map->Parameter {:name :name, :doc "name", :type :string, :required true})
                      (tools/map->Parameter {:name :birth-year, :doc "birth-year", :type :number, :required true})]}
              {:name :add
               :doc  "add"
               :args [(tools/map->Parameter {:name :a, :doc "a", :type :number, :required true})
                      (tools/map->Parameter {:name :b, :doc "b", :type :number, :required true})]}
              {:name :subtract
               :doc  "subtract"
               :args [(tools/map->Parameter {:name :x, :doc "x", :type :number, :required true})
                      (tools/map->Parameter {:name :y, :doc "y", :type :number, :required true})]}]
             (map #(dissoc % :handler) (vals multiple-tools))))))

  (testing "tools can dispatch to external handlers works"
    (let [add-handler         (fn [a b] (+ a b))
          tools-with-handlers (tools/tools
                                (add "" [^{:type :number} a
                                         ^{:type :number} b]
                                     (add-handler a b))
                                (subtract "" [^{:type :number} x
                                              ^{:type :number} y]
                                          (- x y)))
          add-tool (get tools-with-handlers :add)
          subtract-tool (get tools-with-handlers :subtract)]
      (is (= [11 nil] (tools/invoke-tool add-tool {:a 5 :b 6})))
      (is (= [4 nil] (tools/invoke-tool subtract-tool {:x 10 :y 6}))))))
