;; Multi-arch + digest-ref test: platform selection on an index manifest and
;; parse-ref handling of tag vs digest refs.
(ns jolt.test.oci-multiarch
  (:require [jolt.oci :as oci]))

;; parse-ref
(assert (= "registry-1.docker.io" (:registry-api (oci/parse-ref "alpine:3.20"))))
(assert (= "library/alpine" (:repo (oci/parse-ref "alpine:3.20"))))
(assert (= "3.20" (:tag (oci/parse-ref "alpine:3.20"))))
(assert (= "ghcr.io" (:registry-api (oci/parse-ref "ghcr.io/o/r:v1"))))
(assert (= "o/r" (:repo (oci/parse-ref "ghcr.io/o/r:v1"))))
(assert (= "v1" (:tag (oci/parse-ref "ghcr.io/o/r:v1"))))
;; digest ref keeps the digest as the tag (registry accepts sha256:... in the URL)
(let [d "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      r (oci/parse-ref (str "alpine@" d))]
  (assert (= d (:tag r)) "digest ref must become :tag")
  (assert (= "library/alpine" (:repo r))))

;; default platform resolves to the host arch (amd64 here)
(let [img (oci/pull-image "alpine:3.20")]
  (assert (= "amd64" (get-in img [:config "architecture"]))
          (str "default platform should be host arch, got " (get-in img [:config "architecture"]))))

;; explicit arm64 platform resolves the arm64 manifest from the index
(let [img (oci/pull-image "alpine:3.20" {:arch "arm64"})]
  (assert (= "arm64" (get-in img [:config "architecture"]))
          "arm64 platform must resolve the arm64 manifest")
  (assert (seq (:layers img))))

;; unsupported platform fails with the available list
(try
  (oci/pull-image "alpine:3.20" {:arch "mips64le"})
  (throw (ex-info "expected platform error, got a pull" {}))
  (catch clojure.lang.ExceptionInfo e
    (assert (contains? (ex-data e) :available))))

(println "jolt-oci: multi-arch selection + digest refs OK")
