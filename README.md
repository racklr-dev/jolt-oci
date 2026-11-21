# jolt-oci

OCI image pull + unpack for [jolt](https://github.com/jolt-lang/jolt): Docker
Registry v2 client with token auth, manifest/index resolution (amd64), layer
download, and a pure-Clojure tar.gz unpacker.

Boot-tested as the rootfs source for `jolt-libkrun`: pulls `library/alpine:3.20`
and `gcr.io/distroless/base-debian12:latest`, unpacks them, and the result boots
inside a libkrun microVM.

## Usage

```clojure
(require '[jolt.oci :as oci])

(def img (oci/pull "registry-1.docker.io" "library/alpine" "3.20"))
;; => {:config {...} :layers [<gzipped tar bytes> ...]}

(oci/unpack "/tmp/rootfs" (:layers img))
```

## What it handles

- Registry token auth (Bearer challenge), both anonymous and authenticated pulls
- OCI image index + Docker manifest list → linux/amd64 manifest
- gzip layers via `java.util.zip.GZIPInputStream`
- tar entries: files, dirs, symlinks, hardlinks, whiteouts (`.wh.` and opaque
  `.wh..wh..opq`), POSIX modes (set on the host after extraction)

## License

EPL-2.0
