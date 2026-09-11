;; Shared helpers for the e-Gov 法令API v2 raw-preservation fetchers.
;;
;; nbb only (no bb, no .sh, no .mjs -- CLAUDE.md runtime priority). Node's
;; own fetch/crypto/fs are used directly; nothing here depends on a JVM.
(ns lib
  (:require [clojure.string :as str]))

(def fs (js/require "fs"))
(def crypto (js/require "crypto"))

(defn sha256 [buf]
  (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))

(defn sha256-file [p]
  (sha256 (.readFileSync fs p)))

(defn exists? [p] (.existsSync fs p))

(defn mkdirp! [p] (.mkdirSync fs p #js {:recursive true}))

(defn write-file!
  "Writes buf to p, replacing an existing git-annex symlink rather than
   writing through it. Once a dataset has been `datalad save`d, raw/ files
   are symlinks into .git/annex/objects, and those objects are mode 444 --
   a plain writeFileSync follows the link and dies with EACCES. Unlinking
   first turns a re-fetch into a new file that the next `datalad save`
   annexes normally."
  [p ^js buf]
  (when (try (.isSymbolicLink (.lstatSync fs p)) (catch :default _ false))
    (.unlinkSync fs p))
  (.writeFileSync fs p buf))

(defn file-size [p] (.-size (.statSync fs p)))

(defn stabilize
  "Recursively rewrites maps as string-key-sorted maps so pr-str output is
   byte-stable across nbb versions. Same helper (and same reason) as
   global-legislation-datoms bin/build.cljs -- a byte-diff freshness gate is
   only as good as its printer being deterministic."
  [x]
  (cond
    (map? x) (reduce-kv (fn [acc k v] (assoc acc k (stabilize v)))
                        (sorted-map-by #(compare (str %1) (str %2)))
                        x)
    (vector? x) (mapv stabilize x)
    (sequential? x) (map stabilize x)
    :else x))

(defn write-edn! [p x]
  (.writeFileSync fs p (str (pr-str (stabilize x)) "\n")))

(defn read-edn-file [p]
  ;; edamame is what nbb ships for reading EDN with keywords/tagged values.
  (let [edn (js/require "edamame/lib/edamame/core.js")]
    (.parseString edn (.toString (.readFileSync fs p)))))

(defn sleep [ms]
  (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn log [& xs]
  (println (str "[" (.toISOString (js/Date.)) "] " (str/join " " xs))))

(defn fetch-buffer
  "GET url -> js/Buffer, with bounded retries and exponential backoff.
   Returns a promise of {:ok true :buf b} or {:ok false :status n :error s}.
   Never throws: a single dead upstream row must not abort a 9,000-row run.

   :validate is a (fn [buf] -> truthy) applied to a 2xx body. A body that
   fails it is retried with backoff exactly like a 5xx, and reported as
   :invalid-body if it never passes.

   This is not defensive padding. Measured 2026-08-04: under a pool of 6,
   laws.e-gov.go.jp answered 8,548 of 9,536 law_data requests with its HTML
   'page not found' page **and HTTP 200**. Checking resp.ok alone accepted
   every one of them, and the resulting corpus looked complete -- 9,536
   files, no failures logged -- while 90% of it was the same 34 KB error
   page. Nothing downstream would have caught it either; it was only found
   because git-annex deduplicated 9,537 files into 989 keys. A status code
   is not evidence that a body is what was asked for."
  ([url] (fetch-buffer url {}))
  ([url {:keys [tries accept headers validate] :or {tries 4}}]
   (letfn [(attempt [n]
             (-> (js/fetch url
                           (clj->js {:headers (cond-> {"User-Agent" "etzhayyim-legal-corpus/1.0 (+https://github.com/etzhayyim)"}
                                                accept (assoc "Accept" accept)
                                                headers (merge headers))}))
                 (.then (fn [^js resp]
                          (if (.-ok resp)
                            (-> (.arrayBuffer resp)
                                (.then (fn [ab]
                                         (let [buf (js/Buffer.from ab)]
                                           (if (or (nil? validate) (validate buf))
                                             {:ok true :buf buf}
                                             (if (< n tries)
                                               (-> (sleep (* 1000 (js/Math.pow 2 n)))
                                                   (.then (fn [_] (attempt (inc n)))))
                                               {:ok false :status :invalid-body
                                                :bytes (.-length buf)}))))))
                            (if (and (< n tries) (or (>= (.-status resp) 500) (= 429 (.-status resp))))
                              (-> (sleep (* 1000 (js/Math.pow 2 n)))
                                  (.then (fn [_] (attempt (inc n)))))
                              {:ok false :status (.-status resp)}))))
                 (.catch (fn [e]
                           (if (< n tries)
                             (-> (sleep (* 1000 (js/Math.pow 2 n)))
                                 (.then (fn [_] (attempt (inc n)))))
                             {:ok false :error (str e)})))))]
     (attempt 1))))

(defn pooled
  "Runs (f item) over items with at most `n` in flight, resolving to a vector
   of results in completion order. Deliberately simple: no external dep, and
   the pool size is the only politeness knob these fetchers expose."
  [n items f]
  (let [items (vec items)
        total (count items)
        idx (atom 0)
        out (atom [])]
    (letfn [(worker []
              (let [i @idx]
                (if (>= i total)
                  (js/Promise.resolve nil)
                  (do (reset! idx (inc i))
                      (-> (f (nth items i) i)
                          (.then (fn [r] (swap! out conj r) (worker))))))))]
      (-> (js/Promise.all (clj->js (map (fn [_] (worker)) (range (min n total)))))
          (.then (fn [_] @out))))))
