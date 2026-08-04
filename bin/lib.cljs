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

(defn write-file! [p ^js buf]
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
   Never throws: a single dead upstream row must not abort a 9,000-row run."
  ([url] (fetch-buffer url {}))
  ([url {:keys [tries accept headers] :or {tries 4}}]
   (letfn [(attempt [n]
             (-> (js/fetch url
                           (clj->js {:headers (cond-> {"User-Agent" "etzhayyim-legal-corpus/1.0 (+https://github.com/etzhayyim)"}
                                                accept (assoc "Accept" accept)
                                                headers (merge headers))}))
                 (.then (fn [^js resp]
                          (if (.-ok resp)
                            (-> (.arrayBuffer resp)
                                (.then (fn [ab] {:ok true :buf (js/Buffer.from ab)})))
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
