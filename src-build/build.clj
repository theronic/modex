(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.theronic/modex)
(def version "0.2.0") ;(format "0.0.%s" (b/git-count-revs nil)))      ;(def version "0.1.0") once stable.
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uberjar-filename (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  uberjar-filename}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uberjar-filename
           :basis     basis
           :main      'modex.mcp.core})
  (println (str "Compiled uberjar to path: " uberjar-filename)))