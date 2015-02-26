(ns ring.swagger.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [schema.macros :as sm]
            [plumbing.core :refer :all :exclude [update]]
            [schema.utils :as su]
            ring.swagger.json ;; needed for the json-encoders
            [ring.swagger.schema :as schema]
            [ring.swagger.coerce :as coerce]
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [org.tobereplaced.lettercase :as lc]))

;;
;; Models
;;

(s/defschema ResponseMessage {:code Long
                              (s/optional-key :message) String
                              (s/optional-key :responseModel) s/Any})

;;
;; Schema transformations
;;

(defn- full-name [path] (->> path (map name) (map lc/capitalized) (apply str) symbol))

(defn- collect-schemas [keys schema]
  (cond
    (plain-map? schema)
    (let [schema-name (s/schema-name schema)
          schema-meta (if schema-name
                        (meta schema)
                        {:name (full-name keys)})]
      (with-meta
        (into (empty schema)
              (for [[k v] schema
                    :when (jsons/not-predicate? k)
                    :let [keys (if (s/schema-name v)
                                 [(keyword (s/schema-name v))]
                                 (conj keys (my-explicit-schema-key k)))]]
                [k (collect-schemas keys v)]))
        schema-meta))
    
    (valid-container? schema)
    (contain schema (collect-schemas keys (first schema)))

    :else schema))

(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  [schema]
  (collect-schemas [(or (s/schema-name schema) (gensym "schema"))] schema))

(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:id (s/schema-name schema)
       :properties (jsons/properties schema)
       :required required})))

;; NOTE: silently ignores non-map schemas
(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when-let [schema (and
                            (plain-map? x)
                            (s/schema-name x))]
          (swap! schemas assoc schema (if (var? x) @x x)))
        x)
      x)
    @schemas))

;; TODO: use keywords instead of symbols
(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt key #_(comp keyword key) (comp transform val)))
       (into {})))

(defn extract-models [details]
  (let [route-meta (->> details
                        :routes
                        (map :metadata))
        return-models (->> route-meta
                           (keep :return)
                           flatten)
        body-models (->> route-meta
                         (mapcat :parameters)
                         (filter (fn-> :type (= :body)))
                         (keep :model)
                         flatten)
        response-models (->> route-meta
                             (mapcat :responseMessages)
                             (keep :responseModel)
                             flatten)
        all-models (->> (concat body-models return-models response-models)
                        flatten
                        (map with-named-sub-schemas))]
    (->> all-models
         (map (juxt s/schema-name identity))
         (filter (fn-> first))
         (into {}))))

;;
;; Route generation
;;

(defn path-params [s]
  (map (comp keyword second) (re-seq #":(.[^:|(/]*)[/]?" s)))

(defn string-path-parameters [uri]
  (let [params (path-params uri)]
    (if (seq params)
      {:type :path
       :model (zipmap params (repeat String))})))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn generate-nick [{:keys [method uri]}]
  (-> (str (name method) " " uri)
    (str/replace #"/" " ")
    (str/replace #"-" "_")
    (str/replace #":" " by ")
    lc/mixed))

(def swagger-defaults      {:swaggerVersion "1.2" :apiVersion "0.0.1"})
(def resource-defaults     {:produces ["application/json"]
                            :consumes ["application/json"]})
(def api-declaration-keys  [:title :description :termsOfServiceUrl :contact :license :licenseUrl])

(defn join-paths
  "Join several paths together with \"/\". If path ends with a slash,
   another slash is not added."
  [& paths]
  (str/replace (str/replace (str/join "/" (remove nil? paths)) #"([/]+)" "/") #"/$" ""))

(defn context
  "Context of a request. Defaults to \"\", but has the
   servlet-context in the legacy app-server environments."
  [{:keys [servlet-context]}]
  (if servlet-context (.getContextPath servlet-context) ""))

(defn basepath
  "extract a base-path from ring request. Doesn't return default ports
   and reads the header \"x-forwarded-proto\" only if it's set to value
   \"https\". (e.g. your ring-app is behind a nginx reverse https-proxy).
   Adds possible servlet-context when running in legacy app-server."
  [{:keys [scheme server-name server-port headers] :as request}]
  (let [x-forwarded-proto (headers "x-forwarded-proto")
        context (context request)
        scheme (if (= x-forwarded-proto "https") "https" (name scheme))
        port (if (#{80 443} server-port) "" (str ":" server-port))]
    (str scheme "://" server-name port context)))

;;
;; Convert parameters
;;

(defmulti ^:private extract-parameter
  (fn [{:keys [type]}]
    type))

(defmethod extract-parameter :body [{:keys [model type]}]
  (if model
    (vector
      (jsons/->parameter {:paramType type
                          :name (some-> model schema/extract-schema-name str/lower-case)}
                         (jsons/->json model :top true)))))

(defmethod extract-parameter :default [{:keys [model type] :as it}]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key (eval k))]]
      (jsons/->parameter {:paramType type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (jsons/->json v)))))

(defn convert-parameters [parameters]
  (mapcat extract-parameter parameters))

(sm/defn ^:always-validate convert-response-messages [messages :- [ResponseMessage]]
  (for [{:keys [responseModel] :as message} messages]
    (if (and responseModel (schema/named-schema? responseModel))
      (update-in message [:responseModel] (fn [x] (:type (jsons/->json x :top true))))
      (dissoc message :responseModel))))

;;
;; Routing
;;

(defn api-listing [parameters swagger]
  (response
    (merge
      swagger-defaults
      (select-keys parameters [:apiVersion])
      {:info (select-keys parameters api-declaration-keys)
       :authorizations (:authorizations parameters {})
       :apis (for [[api details] swagger]
               {:path (str "/" (name api))
                :description (or (:description details) "")})})))

(defn api-declaration [parameters swagger api basepath]
  (if-let [details (and swagger (swagger api))]
    (response
     (merge
      swagger-defaults
      resource-defaults
      (select-keys parameters [:apiVersion :produces :consumes])
      {:basePath basepath
       :resourcePath "/"
       :models (transform-models (extract-models details))
       :apis (for [{:keys [method uri metadata] :as route} (:routes details)
                   :let [{:keys [return summary notes nickname parameters
                                 responseMessages authorizations]} metadata]]
               {:path (swagger-path uri)
                :operations [(merge
                              (jsons/->json return :top true)
                              {:method (-> method name .toUpperCase)
                               :authorizations (or authorizations {})
                               :summary (or summary "")
                               :notes (or notes "")
                               :nickname (or nickname (generate-nick route))
                               :responseMessages (convert-response-messages responseMessages)
                               :parameters (convert-parameters parameters)})]})}))))


