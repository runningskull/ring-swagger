(ns ring.swagger.common
  (:require [schema.core :as s]
            [plumbing.core :refer [fn-> dissoc-in]]))

(defn remove-empty-keys
  "removes empty keys from a map"
  [m] (into {} (filter (fn-> second nil? not) m)))

(defn name-of
  "Returns name of a Var, String, Named object or nil"
  ^String [x]
  (cond
    (var? x) (-> x meta :name name)
    (string? x) x
    (instance? clojure.lang.Named x) (name x)
    :else nil))

(defn value-of
  "Extracts value of for var, symbol or returns itself"
  [x]
  (cond
    (var? x) (var-get x)
    (symbol? x) (eval x)
    :else x))

(defn extract-parameters
  "Extract parameters from head of the list. Parameters can be:
     1) a map (if followed by any form) [{:a 1 :b 2} :body] => {:a 1 :b 2}
     2) list of keywords & values   [:a 1 :b 2 :body] => {:a 1 :b 2}
     3) else => {}
   Returns a tuple with parameters and body without the parameters"
  [c]
  {:pre [(sequential? c)]}
  (if (and (map? (first c)) (> (count c) 1))
    [(first c) (rest c)]
    (if (keyword? (first c))
      (let [parameters (->> c
                         (partition 2)
                         (take-while (comp keyword? first))
                         (mapcat identity)
                         (apply array-map))
            form       (drop (* 2 (count parameters)) c)]
        [parameters form])
      [{} c])))

(defn plain-map?
  "checks whether input is a map, but not a record"
  [x] (and (map? x) (not (record? x))))

(defn update-in-or-remove-key
  ([m ks f] (update-in-or-remove-key m ks f nil?))
  ([m ks f iff]
    (let [v (f (get-in m ks))]
      (if-not (iff v)
        (assoc-in m ks v)
        (dissoc-in m ks)))))


(clojure.core/defrecord UidKey [k])

(defn uid-key [k]
  (UidKey. k))

(defn uid-key? [k]
  (instance? UidKey k))


(defn my-explicit-schema-key [ks]
  (cond (keyword? ks) ks
        (uid-key? ks) :_id_
        :else (s/explicit-schema-key ks)))
