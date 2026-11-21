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
(defn- oct->long [s] (Long/parseLong (str/trim s) 8))

(defn- challenge [headers] (get headers "www-authenticate"))

(defn- token [registry repo challenge]
  (let [realm (second (re-find #"realm=\"([^\"]+)\"" challenge))
        service (or (second (re-find #"service=\"([^\"]+)\"" challenge)) registry)
        url (str realm "?service=" service "&scope=repository:" repo ":pull")
        r (http/get url {:as :string})]
    (get (json/read-str (:body r)) "token")))

(defn- get-json [url opts]
  (let [r (http/get url (merge {:as :string :throw-exceptions false} opts))]
    (if (= 200 (:status r))
      (json/read-str (:body r))
      (throw (ex-info "registry GET failed" {:url url :status (:status r)})))))

(def accept-types
  "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json")

(defn manifest
  "Fetch an image manifest. Returns a parsed map."
  [registry repo tag]
  (let [url (str "https://" registry "/v2/" repo "/manifests/" tag)
        opts {:headers {"Accept" accept-types}}]
    (let [r (http/get url (merge {:as :string :throw-exceptions false} opts))]
      (if (= 200 (:status r))
        (json/read-str (:body r))
        (get-json url (assoc-in opts [:headers "Authorization"]
                               (str "Bearer " (token registry repo (challenge (:headers r))))))))))

(defn- amd64-manifest [registry repo m]
  (if (get m "manifests")
    (let [entry (first (filter (fn [x] (= "linux/amd64" (str (get-in x ["platform" "os"]) "/" (get-in x ["platform" "architecture"]))))
                               (get m "manifests")))]
      (manifest registry repo (get entry "digest")))
    m))

(defn blob
  "Fetch a layer/config blob by digest. Returns raw bytes."
  [registry repo digest]
  (let [url (str "https://" registry "/v2/" repo "/blobs/" digest)]
    (let [r (http/get url {:as :byte-array :throw-exceptions false})]
      (if (= 200 (:status r))
        (:body r)
        (:body (http/get url {:headers {"Authorization" (str "Bearer " (token registry repo (challenge (:headers r))))}
                              :as :byte-array :throw-exceptions false}))))))

(defn- registry-host? [h]
  (or (str/includes? h ".") (str/includes? h ":") (= h "localhost")))

(defn- registry-api [host]
  (if (= host "docker.io") "registry-1.docker.io" host))

(defn parse-ref
  "Parse an image reference like `alpine:3.21`, `ghcr.io/o/r:v1`,
  or `docker.io/library/alpine:latest`. Returns {:registry-api :repo :tag}."
  [image]
  (let [image (if (str/starts-with? image "docker.io/") (subs image (count "docker.io/")) image)
        [rest tag] (if-let [ls (str/last-index-of image "/")]
                     (let [after (subs image ls)]
                       (if-let [tp (str/last-index-of after ":")]
                         [(subs image 0 (+ ls tp)) (subs image (inc (+ ls tp)))]
                         [image "latest"]))
                     (let [sp (str/split image #":" 2)]
                       (if (= 2 (count sp)) sp [image "latest"])))
        [registry repo] (if-let [slash (str/index-of rest "/")]
                          (let [first (subs rest 0 slash)]
                            (if (registry-host? first)
                              [first (subs rest (inc slash))]
                              ["docker.io" rest]))
                          ["docker.io" (str "library/" rest)])]
    {:registry-api (registry-api registry) :repo repo :tag tag}))

(declare pull)

(defn pull-image
  "Pull an image by full reference (e.g. `alpine:3.20`).
  Returns {:config parsed-json :layers [bytes...]}."
  [image]
  (let [{:keys [registry-api repo tag]} (parse-ref image)]
    (pull registry-api repo tag)))

(defn pull
  "Pull an image. Returns {:config parsed-json :layers [bytes...]}."
  [registry repo tag]
  (let [m (amd64-manifest registry repo (manifest registry repo tag))]
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
                base (subs name (inc (str/last-index-of name "/")))]
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
