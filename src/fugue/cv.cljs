(ns fugue.cv
  (:require [cljs.core.async :as async]))

(defn note->hz [note]
  (* 440.0 (js/Math.pow 2.0 (/ (- note 69.0) 12.0))))

;; note priority is [note] -> note
;; low, high, last, first

(defn midi-x-note
  "Returns a stateful transducer that maps midi events to midi notes based on
  priority-fn, which selects from a list of notes currently down."
  [priority-fn]
  (fn [rf]
    (let [v-down (volatile! [])]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result midi]
         (let [{:keys [type note]} midi
               op (if (= :note-on type) conj #(remove #{%2} %1))
               down (op @v-down note)]
           (print down)
           (vreset! v-down (into [] down))
           (if-let [output (priority-fn down)]
             (rf result output)
             result)))))))

(def midi-x-hz
  (comp
   (midi-x-note last)
   (map note->hz)
   ;; TODO fix this
   (map (fn [hz]
          {:ramps [{:target hz :shape :instant :duration 0}]}))))

(defn midi-x-velo
  "Returns a stateful transducer that maps midi events to midi velocities,
  retriggering if retrigger is truthful"
  [retrigger]
  (fn [rf]
    (let [v-down-count (volatile! 0)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result midi]
         (let [{:keys [type velo]} midi
               note-on (= :note-on type)
               prev-down-count @v-down-count]
           (vswap! v-down-count (if note-on inc dec))
           (if (or (and note-on retrigger (> 1 prev-down-count))
                   (and note-on (= 0 prev-down-count))
                   (and (not note-on) (= 1 prev-down-count)))
             (rf result velo)
             result)))))))

(def midi-x-gate
  "Naive monophonic algorithm, outputs [0, 1)"
  (comp
   (midi-x-velo true)
   (map #(/ % 128))
   ;; TODO deal with this
   (map (fn [l] {:level l}))))

(defn fork
  "Returns a list of copies of chan with optional xforms applied.
  1-arity return two new channels mult'ed from chan untransformed.
  n-arity returns n new channels mult'ed from chan with xforms applied.
  Do not read from chan after calling this."
  ([chan] (fork chan (map identity) (map identity)))
  ([chan & xforms]
   (let [mult (async/mult chan)
         new-chans (map (partial async/chan 1) xforms)]
     (doseq [new-chan new-chans]
       (async/tap mult new-chan))
     new-chans)))
