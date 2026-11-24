;; Standalone jolt-oci smoke test: pull alpine:3.20 and unpack it, then
;; verify a regular file and a symlink from the image landed correctly.
(ns jolt.test.oci-smoke
  (:require [jolt.oci :as oci]
            [clojure.java.io :as io]))

(def rootfs (str (System/getProperty "java.io.tmpdir") "/jolt-oci-test-rootfs"))
(defn path [& parts] (io/file (apply str (interpose "/" parts))))

;; clean slate
(doseq [f (reverse (vec (file-seq (io/file rootfs))))] (.delete f))
(.mkdirs (io/file rootfs))

(def img (oci/pull-image "alpine:3.20"))
(assert (seq (:layers img)) "expected at least one layer")
(oci/unpack rootfs (:layers img))

;; regular file (busybox) present and non-empty
(def busybox (path rootfs "bin" "busybox"))
(assert (.isFile busybox) "/bin/busybox missing")
(assert (pos? (.length busybox)) "/bin/busybox empty")

;; /etc/alpine-release present
(assert (.isFile (path rootfs "etc" "alpine-release")) "/etc/alpine-release missing")

;; a symlink survived unpack (alpine ships /bin/sh -> busybox and /sbin symlink dir)
(let [sh (path rootfs "bin" "sh")]
  (assert (or (java.nio.file.Files/isSymbolicLink (.toPath sh))
              (java.nio.file.Files/isSymbolicLink (.toPath (path rootfs "sbin"))))
          "expected a symlink in the unpacked tree"))

(println "jolt-oci: pull alpine:3.20 + unpack (file, release, symlink) OK")
