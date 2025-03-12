(ns modex.mcp.tools-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [modex.mcp.protocols :as mcp]))

(deftools my-tool-belt
          (greet
            "Greets a person"
            [^{:type :text :doc "Person's name"} name
             ^{:type :number :doc "Person's age"} age]
            (str "Hello, " name "! You are " age " years old."))

          (add
            "Adds two numbers"
            [^{:type :number :doc "First number"} a
             ^{:type :number :doc "Second number"} b]
            (+ a b))

          (subtract
            "Subtracts two numbers"
            [^{:type :number :doc "First number"} a
             ^{:type :number :doc "Second number"} b]
            (external-dec-handler a b))

          (current-time
            "Gets the current time"
            []
            (str (java.time.Instant/now))))

(deftest tools-tests
  (testing "We can define tools via mcp/tools macro that feels like defrecord"
    (let [my-tools (mcp/tools
                     (add "Adds two numbers." [x y] (+ x y)))]
      (testing "tool docstring + arg docs default to names. argument types to :text"
        (is (= [{:name :add
                 :doc  "Adds two numbers."
                 :args [{:name "x", :doc "x", :type :text}
                        {:name "y", :doc "y", :type :text}]}]
               (mcp/list-tools my-tools))))))

  (testing "docstrings are optional (defaults to tool name)")
  (let [my-tools (mcp/tools
                   (add [x y] (+ x y)))]
    (testing "tool docstring + arg docs default to names. argument types to :text"
      (is (= [{:name :add
               :doc  "add"
               :args [{:name "x", :doc "x", :type :text}
                      {:name "y", :doc "y", :type :text}]}]
             (mcp/list-tools my-tools)))))

  (testing "you can call a tool"
    (is (= 11 (mcp/call-tool my-tools :add [5 6]))))

  (testing "Each tool can specify argument type & docstring with preceding metadata. Docstring defaults to arg name."
    (let [tools-with-docs (mcp/tools
                            (add [^{:type :number :doc "First Number"} x
                                  ^{:type :number} y]
                                 (+ x y)))]
      (testing "we can set argument :type"
        (is (= [{:name :add
                 :doc  "add"
                 :args [{:name "x", :doc "First number", :type :number}
                        {:name "y", :doc "y", :type :number}]}]
               (mcp/list-tools tools-with-docs))))))

  (testing "we can define multiple tools"
    (let [multiple-tools (mcp/tools
                           (greet [^{:type :string} name
                                   ^{:type :number} birth-year]
                                  (let [current-year (+ 1900 (.getYear (java.util.Date.)))] ; => 2025])
                                    (str "Hello, " name "! You are " (- current-year birth-year) " years old :)")))
                           (add [^{:type :number} a
                                 ^{:type :number} b]
                                (+ a b))
                           (subtract [^{:type :number} x
                                      ^{:type :number} y]
                                     (+ x y)))])

    (is (= [{:name :greet,
             :doc  "greet",
             :args [{:name "name", :doc "name", :type :string} {:name "birth-year", :doc "birth-year", :type :number}]}
            {:name :add, :doc "add", :args [{:name "a", :doc "a", :type :number} {:name "b", :doc "b", :type :number}]}
            {:name :subtract, :doc "subtract", :args [{:name "x", :doc "x", :type :number} {:name "y", :doc "y", :type :number}]}]
           (mcp/list-tools multiple-tools))))

  (testing "tools can dispatch to external handlers works"
    (let [add-handler         (fn [a b] (+ a b))
          tools-with-handlers (mcp/tools
                                (add [^{:type :number} a
                                      ^{:type :number} b]
                                     (add-handler a b))
                                (subtract [^{:type :number} x
                                           ^{:type :number} y]
                                          (+ x y)))])

    (is (= [{:name :add, :doc "add", :args [{:name "a", :doc "a", :type :number} {:name "b", :doc "b", :type :number}]}
            {:name :subtract, :doc "subtract", :args [{:name "x", :doc "x", :type :number} {:name "y", :doc "y", :type :number}]}]
           (mcp/list-tools tools-with-handlers)))))
