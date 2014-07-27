(ns clojure-miniprofiler.example
  (:use compojure.core)
  (:require [clojure.string :as string]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            clojure.pprint
            [ring.middleware.file-info :refer [file-info-response]]
            [cheshire.core :as json]))

;; TODO
;; display share results (click share in the UI, catch that in middleware)
;; StartMilliseconds should be time since start of req
;; customization (store/root path/callback for authorizing)
;; - regexes matching should be exact string comparison from the given miniprofiler path
;; protocol for the storage

;; storage here
(defn uuid [] (str  (java.util.UUID/randomUUID)))

(defn distance-of-ns-time [ns0 ns1]
  (float (/ (- ns1 ns0) 1000000)))

(defonce in-memory-store (atom {}))

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

(defn save-result [result]
  (swap! in-memory-store assoc (get result "Id") result)
  (clojure.pprint/pprint result))

(defn reconstruct-profile [profiler duration]
  (assoc (:root profiler)
         "Root"
         (assoc
           (:current-timer profiler)
           "DurationMilliseconds"
           duration)
         "DurationMilliseconds" duration))

(defmacro with-recording [req & body]
  `(let [miniprofiler# (create-miniprofiler ~req)]
     (binding [*current-miniprofiler* (atom miniprofiler#)]
     (let [t0# (System/nanoTime)
           result# (do ~@body)
           t1# (System/nanoTime)
           duration# (distance-of-ns-time t0# t1#)]
       (save-result
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

(defn miniprofiler-resource-path [req]
  (let [uri (:uri req)]
    (if (re-matches #".*includes.js" uri)
      "includes.js"
      (if (re-matches #".*includes.tmpl" uri)
        "includes.tmpl"
        (if (re-matches #".*includes.css" uri)
          "includes.css")))))

(defn miniprofiler-results-request? [req]
  (and (= (:request-method req) :post)
       (re-matches #".*results" (:uri req))))

(def fake-response
  {"Id" "21312"
   "Name" "what what"
   "Started" 1000
   "DurationMilliseconds" 1000
   "MachineName" "localhost"
   "Root" {"Id" "12301290321"
           "Name" "ladskfjadlskf"
           "StartMilliseconds" 1001
           "DurationMilliseconds" 1
           "Children" []}
   "ClientTimings" {}})

(defn get-id-from-req [req]
  (let [body (slurp (:body req))
        [_ id] (re-matches #".*id=([a-zA-Z\-0-9]*)&.*" body)]
    id))

(defn miniprofiler-results-response [req]
  (let [id (get-id-from-req req)]
    {:body (json/generate-string (get @in-memory-store id))}))

(defn wrap-miniprofiler [handler]
  (fn [req]
    (if-let [miniprofiler-resource-path (miniprofiler-resource-path req)]
      (->
        (response/resource-response miniprofiler-resource-path)
        (file-info-response req)
        (content-type-response req))
      (if (miniprofiler-results-request? req)
        (miniprofiler-results-response req)
        (let [t0 (System/nanoTime)
              [profile-id response] (with-recording req (handler req))
              t1 (System/nanoTime)
              duration-ms (float (/ (- t1 t0) 1000000))]
          (if (and
                (get-in response [:headers "Content-Type"])
                (re-matches #".*text/html.*" (get-in response [:headers "Content-Type"])))
            (build-miniprofiler-response response duration-ms profile-id)
            response))))))


(defn slow-fn []
  (println "\n\n\n\n\n")
  (trace "Thread/sleep1"
          (Thread/sleep 10)
          (trace "Thread/sleep2"
                 (custom-timing "sql" "query" "SELECT * FROM USERS"
                                (Thread/sleep 50))
                  (Thread/sleep 11)))
  (trace "Thread/sleep3"
          (Thread/sleep 12))
  "<body>lol</body>")

(defroutes app-routes
  (GET "/" [] (slow-fn)))

(def app
  (wrap-miniprofiler app-routes))
