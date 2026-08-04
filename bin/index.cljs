#!/usr/bin/env nbb
;; Derives index/ (small, git-tracked) from raw/ (large, annexed).
;;
;;   index/laws.edn       — one row per UK Public General Act, with the
;;                          ADDRESS of its CLML full text
;;   index/relations.edn  — dependency edges read out of the CLML itself
;;   raw/source-catalog.edn — sha256 of every preserved file
;;
;; Where UK edges come from: legislation.gov.uk marks every cross-reference
;; in the revised text as <Citation URI="http://www.legislation.gov.uk/id/...">.
;; A citation that sits inside <Commentary Type="F"> (a textual amendment
;; footnote) is an AMENDMENT edge -- that commentary is precisely the record
;; of one act having changed another. Everything else is a plain citation.
;; Distinguishing the two is the whole point: "which acts changed this act"
;; and "which acts mention this act" are different questions.
;;
;; Usage: nbb --classpath bin bin/index.cljs
(ns index
  (:require [lib :refer [log write-edn! sha256-file file-size mkdirp!]]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def raw "raw")

(defn act-files []
  (->> (.readdirSync fs (str raw "/acts"))
       (filter #(str/ends-with? % ".xml"))
       sort))

(def act-file-re (js/RegExp. "^ukpga-(\\d+)-(\\d+)\\.xml$"))

(defn tag1 [s t]
  (when-let [m (.match s (js/RegExp. (str "<" t "[^>]*>([\\s\\S]*?)</" t ">")))]
    (str/trim (str/replace (aget m 1) (js/RegExp. "<[^>]+>" "g") ""))))

(defn attr [s tag a]
  (when-let [m (.match s (js/RegExp. (str "<" tag "\\b[^>]*\\b" a "=\"([^\"]*)\"")))]
    (aget m 1)))

(defn law-key [year number] (str "uk-legislation:ukpga/" year "/" number))

;; Citation URIs look like http://www.legislation.gov.uk/id/ukpga/1998/42 and
;; may carry a section suffix (/section/3) which we drop -- the edge is
;; between ACTS, and keeping the pinpoint would explode a few hundred edges
;; into a few hundred thousand without answering a different question.
(def citation-re
  (js/RegExp. "URI=\"http://www\\.legislation\\.gov\\.uk/id/([a-z]+)/(\\d+)/(\\d+)" "g"))

(def commentary-block-re
  (js/RegExp. "<Commentary\\b[^>]*Type=\"F\"[\\s\\S]*?</Commentary>" "g"))

(defn citations-in [s]
  (->> (js/Array.from (.matchAll s citation-re))
       array-seq
       (map (fn [m] [(aget m 1) (aget m 2) (aget m 3)]))
       distinct))

(defn -main []
  (let [files (act-files)
        _ (log "acts on disk:" (count files))
        rows (atom [])
        edges (atom [])]
    (doseq [[i f] (map-indexed vector files)
            :let [m (.match f act-file-re)]
            :when m]
      (let [year (aget m 1)
            number (aget m 2)
            p (str raw "/acts/" f)
            s (.toString (.readFileSync fs p))
            k (law-key year number)
            ;; ukm: metadata sits in a compact header at the top of the file;
            ;; reading only the first 64 KiB keeps this from re-scanning
            ;; multi-megabyte act bodies for a title.
            head (subs s 0 (min 65536 (count s)))]
        (swap! rows conj
               (cond-> {:law/key k
                        :law/jurisdiction "GBR"
                        :law/source-id "uk-legislation-gov-uk"
                        :law/local-id (str "ukpga/" year "/" number)
                        :law/kind :law.kind/statute
                        :law/number (str (attr head "ukm:Year" "Value") " c. " (attr head "ukm:Number" "Value"))
                        :law/title (or (tag1 head "dc:title") (str "UKPGA " year " c. " number))
                        :law/lang "en"
                        :law/status :law.status/in-force
                        :law/url (str "https://www.legislation.gov.uk/ukpga/" year "/" number)
                        :text/path p
                        :text/sha256 (sha256-file p)
                        :text/bytes (file-size p)
                        :text/format "clml-xml"}
                 (tag1 head "dct:valid") (assoc :law/revised-as-of (tag1 head "dct:valid"))
                 (attr head "ukm:EnactmentDate" "Date") (assoc :law/promulgated-at (attr head "ukm:EnactmentDate" "Date"))))
        ;; amendment edges: citations inside textual-amendment commentary
        (let [amended-by (->> (js/Array.from (.matchAll s commentary-block-re))
                              array-seq
                              (mapcat (fn [cm] (citations-in (aget cm 0))))
                              distinct
                              set)
              all (set (citations-in s))]
          (doseq [[typ y n] amended-by
                  :let [other (str "uk-legislation:" typ "/" y "/" n)]
                  :when (not= other k)]
            (swap! edges conj {:rel/from other
                               :rel/to k
                               :rel/kind :law.rel/amends
                               :rel/provenance :from-text-commentary
                               :rel/evidence "CLML Commentary Type=F"
                               :rel/source-id "uk-legislation-gov-uk"}))
          (doseq [[typ y n] (remove amended-by all)
                  :let [other (str "uk-legislation:" typ "/" y "/" n)]
                  :when (not= other k)]
            (swap! edges conj {:rel/from k
                               :rel/to other
                               :rel/kind :law.rel/cites
                               :rel/provenance :from-text-citation
                               :rel/source-id "uk-legislation-gov-uk"}))))
      (when (zero? (mod (inc i) 500)) (log "indexed" (inc i) "/" (count files))))
    (mkdirp! "index")
    (let [rows' (vec (sort-by :law/key @rows))
          edges' (vec (sort-by (juxt :rel/from :rel/kind :rel/to) (distinct @edges)))]
      (write-edn! "index/laws.edn"
                  {:index/id "uk.gov.legislation"
                   :index/jurisdiction "GBR"
                   :index/source-id "uk-legislation-gov-uk"
                   :laws rows'})
      (write-edn! "index/relations.edn"
                  {:index/id "uk.gov.legislation"
                   :index/source-id "uk-legislation-gov-uk"
                   :relations edges'})
      (write-edn! (str raw "/source-catalog.edn")
                  {:catalog/id "uk.gov.legislation"
                   :catalog/source-domain "legislation.gov.uk"
                   :catalog/api "legislation.gov.uk data.feed + data.xml (CLML)"
                   :catalog/license "Open Government Licence v3.0"
                   :catalog/license-tier :tier/a
                   :catalog/scope "every UK Public General Act 1801-2026, revised text as published"
                   :catalog/complete-as-a-class true
                   :catalog/class "UK Public General Acts (ukpga)"
                   :catalog/known-gaps
                   [{:gap :uksi :note "statutory instruments (>100k documents) not fetched in wave 1"}
                    {:gap :devolved :note "asp/anaw/nia/asc devolved legislation not fetched in wave 1"}
                    {:gap :pre-1801 :note "pre-1801 acts exist on legislation.gov.uk and are outside this class"}]
                   :catalog/files
                   (into (vec (for [f (sort (.readdirSync fs (str raw "/feed")))
                                    :let [p (str raw "/feed/" f)]]
                                {:path p :sha256 (sha256-file p) :bytes (file-size p) :kind :year-feed}))
                         (map (fn [r] {:path (:text/path r) :sha256 (:text/sha256 r)
                                       :bytes (:text/bytes r) :kind :law-text
                                       :law-key (:law/key r)}))
                         rows')})
      (log "acts" (count rows') "edges" (count edges')
           "amends" (count (filter #(= :law.rel/amends (:rel/kind %)) edges'))
           "text-bytes" (reduce + 0 (map :text/bytes rows'))))))

(-main)
