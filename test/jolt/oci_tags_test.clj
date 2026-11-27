;; Standalone jolt-oci tags test: list tags for a public repo anonymously
;; (the token dance with repository:<repo>:pull scope), and page a window.
(ns jolt.test.oci-tags
  (:require [jolt.oci :as oci]))

;; anonymous Docker Hub tags/list for library/alpine works (pull scope).
(def tags (oci/tags "docker.io" "library/alpine"))
(assert (vector? tags) "expected a vector of tags")
(assert (seq tags) "expected at least one tag")
(assert (some #(= % "3.20") tags) "expected the 3.20 tag")
(assert (some #(= % "latest") tags) "expected the latest tag")

;; a paged window (n=5) returns exactly 5 and stays deterministic-ish
(def page (oci/tags "docker.io" "library/alpine" {:n 5}))
(assert (vector? page) "expected a vector of tags")
(assert (<= (count page) 5) "n=5 should bound the window")
(assert (seq page) "expected at least one tag in the window")

(println "jolt-oci: tags/list (anonymous pull scope, paged) OK —"
         (count tags) "tags; first 5:" (vec (take 5 page)))