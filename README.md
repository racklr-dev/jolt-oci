# jolt-oci

OCI image pull + unpack for [jolt](https://github.com/jolt-lang/jolt): Docker
Registry v2 client with token auth, multi-arch manifest/index resolution,
layer download, and a pure-Clojure tar.gz unpacker.

Boot-tested as the rootfs source for `jolt-libkrun`: pulls `library/alpine:3.20`
(any arch) and unpacks it into a rootfs that boots inside a libkrun microVM.

## Usage

```clojure
(require '[jolt.oci :as oci])

;; by full reference (defaults to the host arch, linux)
(def img (oci/pull-image "alpine:3.20"))
;; => {:config {:architecture "amd64" ...} :layers [<gzipped tar bytes> ...]}

;; explicit platform
(def arm (oci/pull-image "alpine:3.20" {:arch "arm64"}))

;; digest reference (immutable pin)
(def pinned (oci/pull-image "alpine@sha256:0123..."))

(oci/unpack "/tmp/rootfs" (:layers img))
```

## What it handles

- Registry token auth (Bearer challenge), anonymous and authenticated pulls
- OCI image index + Docker manifest list → per-platform manifest
  (host arch default: x86_64→amd64, aarch64→arm64; `:arch` overrides)
- Image references: `alpine`, `alpine:3.20`, `alpine@sha256:...`,
  `library/alpine:3.20`, `docker.io/library/alpine:latest`, `ghcr.io/o/r:v1`
- gzip layers via `java.util.zip.GZIPInputStream`
- tar entries: files, dirs, symlinks, hardlinks, whiteouts (`.wh.` and opaque
  `.wh..wh..opq`), POSIX modes (set on the host after extraction)

## API

- `parse-ref` — parse an image reference → `{:registry-api :repo :tag}`
  (`:tag` may be a digest).
- `manifest` / `platform-manifest` — fetch a manifest, resolve a platform.
- `blob` — fetch a layer/config by digest.
- `pull-image` / `pull` — pull an image (+ optional platform).
- `unpack` / `unpack-layer` — unpack gzipped layer tars into a rootfs.

## License

EPL-2.0
