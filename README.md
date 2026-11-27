# jolt-oci — OCI image pull and unpack

OCI image pull and unpack for [jolt](https://github.com/jolt-lang/jolt): a
Docker Registry v2 client with token auth, multi-arch manifest/index
resolution, layer download, and a pure-Clojure tar.gz unpacker.

Boot-tested as the rootfs source for
[jolt-libkrun](https://github.com/racklr-dev/jolt-libkrun): it pulls
`library/alpine:3.20` (any arch) and unpacks it into a rootfs that boots
inside a libkrun microVM.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ to run the tests
- [jolt-lang/http-client](https://github.com/jolt-lang/http-client) — a pinned
  git dependency, resolved automatically by `deps.edn`
- Network access to a registry for real pulls

## Build

Nothing to compile. Run the suite:

```sh
jolt run test/jolt/oci_smoke_test.clj
jolt run test/jolt/oci_multiarch_test.clj
```

## Usage

```clojure
{:deps {racklr-dev/jolt-oci
        {:git/url "https://github.com/racklr-dev/jolt-oci"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.oci :as oci])

(def img (oci/pull-image "alpine:3.20"))
;; => {:config {:architecture "amd64" ...} :layers [<gzipped tar bytes> ...]}

(def arm (oci/pull-image "alpine:3.20" {:arch "arm64"}))
(def pinned (oci/pull-image "alpine@sha256:0123..."))  ; immutable digest pin

(oci/unpack "/tmp/rootfs" (:layers img))
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `parse-ref` | `ref` | `{:registry-api :repo :tag}` | throws on an unparseable ref |
| `manifest` | `registry repo tag` | the manifest JSON | registry/HTTP errors |
| `platform-manifest` | `manifest & [arch os]` | the per-platform manifest | — |
| `blob` | `registry repo digest` | blob bytes | registry/HTTP errors |
| `pull-image` | `image & opts` | `{:config ... :layers [...]}` | registry/HTTP errors |
| `pull` | `image & opts` | `{:config parsed-json :layers [bytes...]}` | registry/HTTP errors |
| `tags` | `registry repo & opts` | the tag vector | registry/HTTP errors |
| `catalog` | `registry & opts` | `{:repositories [...] :next token}` | registry/HTTP errors (Docker Hub denies it anonymously) |
| `unpack-layer` | `rootfs layer-gz` | nil (writes into `rootfs`) | tar extraction errors |
| `unpack` | `rootfs layers` | nil (writes into `rootfs`) | tar extraction errors |

Handled: Bearer token auth (anonymous and authenticated), image index +
manifest-list → per-platform manifest (host arch default: x86_64→amd64,
aarch64→arm64, `:arch` overrides), references from `alpine` to
`ghcr.io/o/r:v1`, tag listing (`tags/list`, paged), the registry catalog
(`_catalog`, where the registry permits it), gzip layers, and tar entries —
files, dirs, symlinks, hardlinks, whiteouts (`.wh.` and `.wh..wh..opq`),
POSIX modes.

## Desktop bases

`examples/desktop-base.clj` pulls and unpacks the alpine base that the
jolt-microvm desktop examples build on. This library stops at the bootable
rootfs; jolt-microvm seeds the desktop apk packages and boots it.

## Status

beta — the smoke test pulls and unpacks a real image, and the multi-arch test
covers index→manifest resolution across architectures. Digest-pinned pulls and
whiteout handling are exercised; private-registry auth is not covered.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [OCI image spec](https://github.com/opencontainers/image-spec) — the image format
- [OCI distribution spec](https://github.com/opencontainers/distribution-spec) — the registry v2 protocol
- [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) — the consumer that boots these rootfs
