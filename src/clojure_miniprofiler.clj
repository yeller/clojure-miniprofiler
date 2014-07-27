(ns clojure-miniprofiler
  (:require [clojure.string :as string]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            [ring.middleware.file-info :refer [file-info-response]]
            [cheshire.core :as json]
            [clojure-miniprofiler.store :refer :all]))

;; storage here
(deftype InMemoryStore [store]
  Storage
  (save [_ profile]
    (swap! store assoc (get profile "Id") profile))
  (fetch [_ id]
    (get @store id)))

(defn in-memory-store []
  (InMemoryStore.
    (atom {})))

(defn uuid [] (str  (java.util.UUID/randomUUID)))

(defn distance-of-ns-time [ns0 ns1]
  (float (/ (- ns1 ns0) 1000000)))

(def ^:dynamic *current-miniprofiler* nil)

(defn current-ms []
  (float (/ (System/nanoTime) 1000000)))

(defn ms-since-start []
  (distance-of-ns-time (get @*current-miniprofiler* :start-ns) (System/nanoTime)))

(defn add-child [parent-timer section-name]
  {"Id" (uuid)
   "Name" section-name
   "StartMilliseconds" (ms-since-start)
   "Children" []})

(defmacro trace [section-name & body]
  `(if *current-miniprofiler*
     (let [parent-timer# (:current-timer @*current-miniprofiler*)
           new-timer# (add-child parent-timer# ~section-name)
           t0# (System/nanoTime)]
       (swap! *current-miniprofiler* assoc :current-timer new-timer#)
       (try
         (do ~@body)
         (finally
           (let [t1# (System/nanoTime)
                 duration# (distance-of-ns-time t0# t1#)]
             (swap! *current-miniprofiler*
                    (fn [current-miniprofiler#]
                      (assoc current-miniprofiler#
                             :current-timer
                             (assoc parent-timer#
                                    "Children"
                                    (conj (get parent-timer# "Children")
                                          (assoc (:current-timer current-miniprofiler#)
                                                 "DurationMilliseconds" duration#))))))))))
     (do ~@body)))

(defn create-custom-timing [execute-type command-string stacktrace-info]
  {"Id" (uuid)
   "ExecuteType" execute-type
   "CommandString" command-string
   "StackTraceSnippet" stacktrace-info
   "StartMilliseconds" (ms-since-start)})

(defmacro custom-timing [call-type execute-type command-string & body]
  `(if *current-miniprofiler*
    (let [t0# (System/nanoTime)
          stacktrace-info# (str ~*file*  ":" ~(:line (meta &form)))
          custom-timing# (create-custom-timing ~execute-type ~command-string stacktrace-info#)]
      (try
        (do ~@body)
        (finally
          (let [t1# (System/nanoTime)
                duration# (distance-of-ns-time t0# t1#)]
            (swap! *current-miniprofiler*
                   (fn [current-miniprofiler#]
                     (assoc current-miniprofiler#
                            :current-timer
                            (assoc (:current-timer current-miniprofiler#)
                                   "CustomTimings"
                                   (assoc (get (:current-timer current-miniprofiler#) "CustomTimings" {})
                                          ~call-type
                                          (conj (get-in current-miniprofiler# [:current-timer "CustomTimings" ~call-type] [])
                                                (assoc custom-timing#
                                                       "DurationMilliseconds" duration#)))))))))))
    (do ~@body)))

(defn create-miniprofiler [req]
  {:current-timer
   {"Id" (uuid)
    "Name" "ring handler"
    "StartMilliseconds" 0
    "Children" []}
   :root
   {"Id" (uuid)
    "Name" (str (.toUpperCase (name (:request-method req))) " " (:uri req))
    "Started" (current-ms)
    "MachineName" (.getHostName (java.net.InetAddress/getLocalHost))
    "ClientTimings" {}}
   :start-ns (System/nanoTime)})

(defn reconstruct-profile [profiler duration]
  (assoc (:root profiler)
         "Root"
         (assoc
           (:current-timer profiler)
           "DurationMilliseconds"
           duration)
         "DurationMilliseconds" duration))

(defmacro with-recording [options req & body]
  `(let [miniprofiler# (create-miniprofiler ~req)]
     (binding [*current-miniprofiler* (atom miniprofiler#)]
     (let [t0# (System/nanoTime)
           result# (do ~@body)
           t1# (System/nanoTime)
           duration# (distance-of-ns-time t0# t1#)]
       (save (:store ~options)
             (reconstruct-profile @*current-miniprofiler* duration#))
       [(get-in @*current-miniprofiler* [:root "Id"]) result#]))))

(def miniprofiler-script-tag
  (slurp (:body (response/resource-response "include.partial.html"))))

(defn build-miniprofiler-script-tag [duration-ms profiler-id]
  (reduce
    (fn [result [k v]]
      (string/replace result (re-pattern (str "\\{" k "\\}")) (str v)))
    miniprofiler-script-tag
    {"path" "miniprofiler/"
     "version" "0.0.1"
     "currentId" profiler-id
     "ids" profiler-id
     "showControls" "true"
     "authorized" "true"
     "startHidden" "false"
     "position" "right"
     "showTrivial" "true"
     "showChildren" "true"
     "maxTracesToShow" "100"
     "trivialMilliseconds" 1}))

(defn build-miniprofiler-response [response duration-ms profiler-id]
  (if (re-matches #".*</body>.*" (:body response))
    (assoc response
           :body
           (string/replace
             (:body response)
             #"</body>"
             (build-miniprofiler-script-tag duration-ms profiler-id)))
    response))

(defn miniprofiler-resource-path [req options]
  (let [^String uri (:uri req)]
    (if (.startsWith uri (:base-path options))
      (if (= (str (:base-path options) "/includes.js") uri)
        "includes.js"
        (if (= (str (:base-path options) "/includes.tmpl") uri)
          "includes.tmpl"
          (if (= (str (:base-path options) "/includes.css") uri)
            "includes.css"))))))

(defn miniprofiler-results-request? [req options]
  (= (str (:base-path options) "/results") (:uri req)))

(defn get-id-from-req [req]
  (if (= (:request-method req) :post)
    (let [body (slurp (:body req))
          [_ id] (re-matches #".*id=([a-zA-Z\-0-9]*)&.*" body)]
      id)
    (string/replace (:query-string req) "id=" "")))

(defn render-share [id options]
  (let [resource (response/resource-response "share.html")
        result (fetch (:store options) id)]
    {:body
     (reduce
       (fn [result [k v]]
         (string/replace result (re-pattern (str "\\{" k "\\}")) (str v)))
       (slurp (:body resource))
       {"name" (get result "Name")
        "duration" (get result "DurationMilliseconds")
        "json" (json/generate-string result)
        "includes" (build-miniprofiler-script-tag (get result "DurationMilliseconds") (get result "Id"))})}))

(defn miniprofiler-results-response [req options]
  (let [id (get-id-from-req req)]
    (if (= (:request-method req) :post)
      {:body (json/generate-string (fetch (:store options) id))}
      (render-share id options))))

(def default-options
  {:base-path "/miniprofiler"
   :authorized? (fn [req] (= (:server-name req) "localhost"))})

(defn wrap-miniprofiler
  [handler opts]
  (let [options (merge default-options opts)]
    (fn [req]
      (if ((:authorized? options) req)
        (if-let [miniprofiler-resource-path (miniprofiler-resource-path req options)]
          (->
            (response/resource-response miniprofiler-resource-path)
            (file-info-response req)
            (content-type-response req))
          (if (miniprofiler-results-request? req options)
            (miniprofiler-results-response req options)
            (let [t0 (System/nanoTime)
                  [profile-id response] (with-recording options req (handler req))
                  t1 (System/nanoTime)
                  duration-ms (float (/ (- t1 t0) 1000000))]
              (if (and (get-in response [:headers "Content-Type"])
                       (re-matches #".*text/html.*" (get-in response [:headers "Content-Type"])))
                (build-miniprofiler-response response duration-ms profile-id)
                response))))
        (handler req)))))
