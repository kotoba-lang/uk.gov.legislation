#!/usr/bin/env nbb
;; Preserves legislation.gov.uk UK Public General Acts into raw/.
;;
;;   raw/feed/ukpga-<year>.atom  — GET /ukpga/<year>/data.feed (all acts of
;;                                 that year, with ukm: metadata)
;;   raw/acts/ukpga-<year>-<n>.xml
;;                               — GET /ukpga/<year>/<n>/data.xml, the full
;;                                 CLML text of the act as currently revised
;;
;; Scope (wave 1, complete as a class): every UK Public General Act from 1801
;; to the current year. UKSI (statutory instruments, >100k documents) is a
;; deliberate wave-2 gap, recorded in source-catalog.edn rather than left to
;; be inferred from an absence.
;;
;; The CLML carries its own dependency edges inline: <Citation URI="...">
;; elements point at other legislation.gov.uk identifiers, and the ones sitting
;; inside amendment commentary are amendment edges. Extraction happens in the
;; consumer (global-legislation-datoms), never here -- raw/ stays byte-identical
;; to what the upstream served, so its sha256 means something.
;;
;; Usage: nbb --classpath bin bin/fetch.cljs [--pool N] [--from YYYY] [--limit N]
(ns fetch
  (:require [lib :refer [fetch-buffer log mkdirp! exists? write-file! pooled
                         sleep file-size]]
            [clojure.string :as str]))

(def base "https://www.legislation.gov.uk")
(def raw "raw")

(def args (vec *command-line-args*))
(defn arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (js/parseInt (nth args (inc i)))
    default))

(def pool-size (arg "--pool" 4))
(def from-year (arg "--from" 1801))
(def to-year (arg "--to" 2026))
(def hard-limit (arg "--limit" 0))

(defn feed-path [year] (str raw "/feed/ukpga-" year ".atom"))
(defn act-path [year n] (str raw "/acts/ukpga-" year "-" n ".xml"))

(defn fetch-feed [year]
  (let [p (feed-path year)]
    (if (and (exists? p) (pos? (file-size p)))
      (js/Promise.resolve {:year year :skipped true})
      ;; results-count=500 asks for the whole year in one page; every UKPGA
      ;; year since 1801 is well under that (the busiest is ~140 acts).
      (-> (fetch-buffer (str base "/ukpga/" year "/data.feed?results-count=500"))
          (.then (fn [{:keys [ok buf status error]}]
                   (if ok
                     (do (write-file! p buf) {:year year :bytes (.-length buf)})
                     {:year year :failed (or status error)})))))))

(defn parse-feed-ids
  "Pulls the act identifiers out of an Atom feed without an XML parser: every
   entry carries <id>http://www.legislation.gov.uk/id/ukpga/<year>/<n></id>.
   A regex is adequate and dependency-free because we are reading OUR OWN
   just-fetched bytes for a single, stable, machine-generated shape -- not
   parsing arbitrary user XML."
  [year]
  (let [s (.toString (.readFileSync (js/require "fs") (feed-path year)))
        re (js/RegExp. (str "/id/ukpga/" year "/(\\d+)\\b") "g")]
    ;; matchAll returns an iterator, not an array-like -- array-seq silently
    ;; yields nothing on it (measured: 31 <id> elements, 0 parsed ids).
    (->> (js/Array.from (.matchAll s re))
         array-seq
         (map #(js/parseInt (aget % 1)))
         distinct
         sort
         vec)))

(defn fetch-act [[year n]]
  (let [p (act-path year n)]
    (if (and (exists? p) (pos? (file-size p)))
      (js/Promise.resolve {:id [year n] :skipped true})
      (-> (fetch-buffer (str base "/ukpga/" year "/" n "/data.xml"))
          (.then (fn [{:keys [ok buf status error]}]
                   (if ok
                     (do (write-file! p buf) {:id [year n] :bytes (.-length buf)})
                     {:id [year n] :failed (or status error)})))))))

(defn -main []
  (mkdirp! (str raw "/feed"))
  (mkdirp! (str raw "/acts"))
  (let [years (range from-year (inc to-year))]
    (-> (pooled 3 years (fn [y _] (-> (fetch-feed y) (.then (fn [r] (sleep 100) r)))))
        (.then (fn [rs]
                 (let [failed (filter :failed rs)]
                   (log "feeds:" (count (remove #(or (:failed %) (:skipped %)) rs)) "fetched,"
                        (count (filter :skipped rs)) "cached," (count failed) "failed")
                   (when (seq failed)
                     (log "FEED FAILURES:" (str/join "," (map :year (take 20 failed))))))
                 (let [ids (vec (mapcat (fn [y]
                                          (when (exists? (feed-path y))
                                            (map (fn [n] [y n]) (parse-feed-ids y))))
                                        years))
                       ids (if (pos? hard-limit) (vec (take hard-limit ids)) ids)]
                   (log "fetching" (count ids) "acts with pool" pool-size)
                   (-> (pooled pool-size ids
                               (fn [id i]
                                 (-> (fetch-act id)
                                     (.then (fn [r]
                                              (when (zero? (mod (inc i) 100))
                                                (log "progress" (inc i) "/" (count ids)))
                                              r)))))
                       (.then (fn [rs]
                                (let [failed (filter :failed rs)]
                                  (log "acts done. fetched" (count (remove #(or (:failed %) (:skipped %)) rs))
                                       "skipped" (count (filter :skipped rs))
                                       "failed" (count failed))
                                  (when (seq failed)
                                    (log "FAILED:" (str/join "," (map (comp str :id) (take 40 failed))))))))))))
        (.catch (fn [e] (log "FATAL" (str e)) (set! (.-exitCode js/process) 1))))))

(-main)
