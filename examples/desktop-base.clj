;; Pull the alpine base that the jolt-microvm desktop examples build on and
;; unpack it. Package seeding and boot are jolt-microvm's job; jolt-oci stops
;; at the bootable rootfs.
;; Run from the jolt-oci repo root:
;;   jolt run examples/desktop-base.clj
(require '[jolt.oci :as oci])

(def img (oci/pull-image "alpine:3.20"))
(oci/unpack "/tmp/jolt-desktop-base" (:layers img))
(println "alpine:3.20 unpacked to /tmp/jolt-desktop-base")
(println "arch:" (get-in img [:config "architecture"])
         "layers:" (count (:layers img)))
