;; OCI image pull + unpack (Docker Registry v2 + tar.gz layers).
(ns jolt.oci
  (:require [jolt.http-client :as http]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File FileOutputStream]
           [java.util.zip GZIPInputStream]
           [java.nio.file Files Paths LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- gunzip [bs]
  (let [gz (GZIPInputStream. (ByteArrayInputStream. bs))
        out (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [n (.read gz buf)]
      (when (not (neg? n))
        (.write out buf 0 n)
        (recur (.read gz buf))))
    (.toByteArray out)))

(defn- bstr [bs] (String. bs "UTF-8"))
(defn- slice [bs off len] (java.util.Arrays/copyOfRange bs off (+ off len)))
(defn- oct->long [s]
  (let [t (str/trim s)]
    (if (empty? t) 0 (Long/parseLong t 8))))

(defn- challenge [headers] (get headers "www-authenticate"))

(def ^:private transient-statuses #{429 500 502 503 504})

(defn- backoff-ms [attempt] (* 1000 (bit-shift-left 1 (min attempt 4))))

(defn- retry-get
  "GET url with retry on transient statuses (Docker Hub rate limits, 5xx).
  :throw-exceptions is forced false; other opts pass through to http/get."
  [url opts]
  (loop [attempt 0]
    (let [r (http/get url (merge {:throw-exceptions false} opts))]
      (if (and (transient-statuses (:status r)) (< attempt 4))
        (do (Thread/sleep (backoff-ms attempt))
            (recur (inc attempt)))
        r))))

(defn- token [registry repo challenge]
  (let [realm (second (re-find #"realm=\"([^\"]+)\"" challenge))
        service (or (second (re-find #"service=\"([^\"]+)\"" challenge)) registry)
        url (str realm "?service=" service "&scope=repository:" repo ":pull")
        r (retry-get url {:as :string})]
    (when-not (= 200 (:status r))
      (throw (ex-info "registry token fetch failed" {:url url :status (:status r)})))
    (get (json/read-str (:body r)) "token")))

(defn- registry-get
  "Authenticated Docker Registry v2 GET: retries transient failures, does the
  token dance on 401, and returns the 200 response map or throws."
  [registry repo url opts]
  (loop [auth nil]
    (let [opts (if auth (assoc-in opts [:headers "Authorization"] (str "Bearer " auth)) opts)
          r (retry-get url opts)]
      (cond
        (= 200 (:status r)) r
        (and (nil? auth) (= 401 (:status r))) (recur (token registry repo (challenge (:headers r))))
        :else (throw (ex-info "registry GET failed" {:url url :status (:status r)}))))))

(def accept-types
  "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json")

(defn manifest
  "Fetch an image manifest. Returns a parsed map."
  [registry repo tag]
  (let [url (str "https://" registry "/v2/" repo "/manifests/" tag)
        r (registry-get registry repo url {:headers {"Accept" accept-types} :as :string})]
    (json/read-str (:body r))))

(defn host-arch
  "OCI architecture name for this host (the guest is always linux)."
  []
  (case (str/lower-case (or (System/getProperty "os.arch") "amd64"))
    ("amd64" "x86_64") "amd64"
    ("aarch64" "arm64") "arm64"
    ("x86" "i386" "i686") "386"
    ("arm") "arm"
    ("ppc64le") "ppc64le"
    ("s390x") "s390x"
    "amd64"))

(defn platform-manifest
  "Resolve an index/list manifest to the manifest for platform (defaults to the
  host arch, linux). Returns m unchanged when it is already a single manifest."
  [registry repo m & [{:keys [os arch] :or {os "linux" arch (host-arch)}}]]
  (if-let [entries (get m "manifests")]
    (let [entry (or (some (fn [x]
                            (let [p (get x "platform")]
                              (and (= os (get p "os"))
                                   (= arch (get p "architecture"))
                                   x)))
                          entries)
                    (throw (ex-info "no image manifest for platform"
                                    {:os os :arch arch
                                     :available (mapv (fn [x] (get-in x ["platform" "architecture"])) entries)})))]
      (manifest registry repo (get entry "digest")))
    m))

(defn blob
  "Fetch a layer/config blob by digest. Returns raw bytes."
  [registry repo digest]
  (let [url (str "https://" registry "/v2/" repo "/blobs/" digest)
        r (registry-get registry repo url {:as :byte-array})]
    (:body r)))

(defn- registry-host? [h]
  (or (str/includes? h ".") (str/includes? h ":") (= h "localhost")))

(defn- registry-api [host]
  (if (= host "docker.io") "registry-1.docker.io" host))

(defn parse-ref
  "Parse an image reference. Supports `alpine`, `alpine:3.21`, `alpine@sha256:...`,
  `library/alpine:3.21`, `docker.io/library/alpine:latest`, `ghcr.io/o/r:v1`.
  Returns {:registry-api :repo :tag} where :tag may be a digest."
  [image]
  (let [image (if (str/starts-with? image "docker.io/") (subs image (count "docker.io/")) image)
        [image digest] (if-let [i (str/index-of image "@")]
                         [(subs image 0 i) (subs image (inc i))]
                         [image nil])
        [image tag] (let [slash (str/last-index-of image "/")
                          colon (str/last-index-of image ":")]
                      (if (and colon (or (nil? slash) (> colon slash)))
                        [(subs image 0 colon) (subs image (inc colon))]
                        [image "latest"]))
        [registry repo] (if-let [slash (str/index-of image "/")]
                          (let [first (subs image 0 slash)]
                            (if (registry-host? first)
                              [first (subs image (inc slash))]
                              ["docker.io" image]))
                          ["docker.io" (str "library/" image)])]
    {:registry-api (registry-api registry) :repo repo :tag (or digest tag)}))

(declare pull)

(defn pull-image
  "Pull an image by full reference (e.g. alpine:3.20), optionally for a
  platform: (pull-image image {:arch :arm64}). Returns
  {:config parsed-json :layers [bytes...]}."
  ([image] (pull-image image {}))
  ([image platform]
   (let [{:keys [registry-api repo tag]} (parse-ref image)]
     (pull registry-api repo tag platform))))

(defn pull
  "Pull an image. platform opts: {:os :linux :arch :amd64} (defaults to the
  host arch, linux). Returns {:config parsed-json :layers [bytes...]}."
  [registry repo tag & [platform]]
  (let [m (platform-manifest registry repo (manifest registry repo tag) platform)]
    {:config (json/read-str (bstr (blob registry repo (get-in m ["config" "digest"]))))
     :layers (mapv (fn [l] (blob registry repo (get l "digest"))) (get m "layers"))}))

(defn- whiteout?
  [basename]
  (and (str/starts-with? basename ".wh.") (not= ".wh..wh..opq" basename)))

(defn- write-file! [path data]
  (let [f (File. path)]
    (.mkdirs (.getParentFile f))
    (let [o (FileOutputStream. f)]
      (.write o data)
      (.close o))))

(defn- mode->rwx [m]
  (let [bit (fn [n c] (if (zero? (bit-and m n)) "-" c))]
    (str (bit 0400 "r") (bit 0200 "w") (bit 0100 "x")
         (bit 0040 "r") (bit 0020 "w") (bit 0010 "x")
         (bit 0004 "r") (bit 0002 "w") (bit 0001 "x"))))

(defn- set-perms! [path mode]
  (Files/setPosixFilePermissions (Paths/get path (into-array String []))
                                 (PosixFilePermissions/fromString (mode->rwx mode))))

(defn unpack-layer
  "Unpack one gzipped layer tar into rootfs, honouring whiteouts."
  [rootfs layer-gz]
  (let [tar (gunzip layer-gz)
        n (alength tar)]
    (loop [off 0]
      (let [name (str/replace (bstr (slice tar off 100)) #"\u0000.*$" "")]
        (when (and (< (+ off 512) n) (not (empty? name)))
          (let [size (oct->long (str/replace (bstr (slice tar (+ off 124) 12)) #"\u0000.*$" ""))
                mode (oct->long (str/replace (bstr (slice tar (+ off 100) 8)) #"\u0000.*$" ""))
                type (char (aget tar (+ off 156)))
                path (str rootfs "/" name)
                data-off (+ off 512)
                linkname (str/replace (bstr (slice tar (+ off 157) 100)) #"\u0000.*$" "")
                base (if-let [ls (str/last-index-of name "/")]
                       (subs name (inc ls))
                       name)]
            (cond
            (and (= type \0) (whiteout? base))
            (.delete (File. (str rootfs "/" (subs name 0 (inc (str/last-index-of name "/"))) (subs base 4))))

            (= ".wh..wh..opq" base)
            (let [d (File. (str rootfs "/" (subs name 0 (str/last-index-of name "/"))))]
              (doseq [f (reverse (seq (.listFiles d)))] (.delete f)))

            (= type \5)
            (do (.mkdirs (File. path))
                (set-perms! path mode))

            (= type \2)
            (do (.delete (File. path))
                (Files/createSymbolicLink (Paths/get path (into-array String []))
                                          (Paths/get linkname (into-array String []))
                                          (into-array FileAttribute [])))

            (= type \1)
            (do (.delete (File. path))
                (Files/createLink (Paths/get path (into-array String []))
                                  (Paths/get (str rootfs "/" linkname) (into-array String []))))

            (or (= type \0) (= type \7))
            (do (write-file! path (slice tar data-off size))
                (set-perms! path mode))

            :else nil)
          (recur (+ off 512 (* 512 (quot (+ size 511) 512))))))))))

(defn unpack
  "Unpack all layers into rootfs (dirs are created)."
  [rootfs layers]
  (doseq [l layers] (unpack-layer rootfs l))
  rootfs)
