(ns build
  (:require [clojure.tools.build.api :as b]))

;(defn get-git-tag [] (b/git-process {:git-args "describe --tags --exact-match" :dir "."}))

;(def re-version-format #"(\d+)\.(\d+)\.(\d+)")

;(comment
;  (get-git-tag)
;  (re-matches re-version-format "0.2.0")
;  (re-matches re-version-format "0.x.0"))

;(defn valid-version? [version] (first (re-seq re-version-format version)))

(def lib 'com.theronic/modex)
(def version "0.3.0") ;(get-git-tag)) ;(format "0.0.%s" (b/git-count-revs nil)))      ;(def version "0.1.0") once stable.
;(assert (valid-version? version) "Version expects a git tag in format major.minor.thingy") ; should probably be dates.
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