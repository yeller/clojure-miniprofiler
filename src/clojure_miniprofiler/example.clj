(ns clojure-miniprofiler.example
  (:use compojure.core)
  (:require [clojure.string :as string]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            clojure.pprint
            [ring.middleware.file-info :refer [file-info-response]]
            [cheshire.core :as json]))

;; TODO
;; figure out call tracing
;; replace clojure stuff with deftyped classes for speed
;; customization (store/root path/callback for authorizing)
;; - regexes matching should be exact string comparison from the given miniprofiler path
;; protocol for the storage

;; storage here

(defn uuid [] (str  (java.util.UUID/randomUUID)))

(defn distance-of-ns-time [ns0 ns1]
  (float (/ (- ns1 ns0) 1000000)))

(defonce in-memory-store (atom {}))

(def ^:dynamic *current-miniprofiler* nil)
(def ^:dynamic *current-miniprofiler-path* ["Root"])

(defn current-ms []
  (float (/ (System/nanoTime) 1000000)))

(defn create-miniprofiler [req]
  {"Id" (uuid)
   "Name" (:uri req)
   "Started" (current-ms)
   "MachineName" "localhost"
   "Root" {"Id" (uuid)
           "Name" "ring handler"
           "StartMilliseconds" (current-ms)
           "Children" []}
   "ClientTimings" {}})

(defn save-result [result]
  (swap! in-memory-store assoc (get result "Id") result)
  (clojure.pprint/pprint result))

(defmacro with-recording [req & body]
  `(binding [*current-miniprofiler* (atom (create-miniprofiler ~req))
             *current-miniprofiler-path* (atom ["Root"])]
     (let [t0# (System/nanoTime)
           result# (do ~@body)
           t1# (System/nanoTime)
           duration# (distance-of-ns-time t0# t1#)]
       (save-result
         (assoc-in
           (assoc @*current-miniprofiler*
                  "DurationMilliseconds"
                  duration#)
           ["Root" "DurationMilliseconds"] duration#))
       [(get @*current-miniprofiler* "Id") result#])))

(defn add-child [miniprofiler duration section-name current-path start-ms]
  (loop [node miniprofiler
         path current-path]
    (if (= (count path) 1)

      )
    )
  )

(defmacro record [section-name & body]
  `(do
     (swap! *current-miniprofiler-path* conj ~section-name)
     (let [this-uuid (uuid)
           t0# (System/nanoTime)
           result# (do ~@body)
           t1# (System/nanoTime)
           duration# (distance-of-ns-time t0# t1#)]
       (swap! *current-miniprofiler-path* drop-last)
       (swap! *current-miniprofiler*
              (fn [existing#]
                (add-child existing# duration# ~section-name @*current-miniprofiler-path* t0#)
                (let [current-children-count# (count (get-in existing# (conj (vec @*current-miniprofiler-path*) "Children")))]
                  (assoc-in existing# (conj (vec @*current-miniprofiler-path*) "Children" current-children-count#)
                            {"Id" this-uuid
                             "Name" ~section-name
                             "StartMilliseconds" t0#
                             "DurationMilliseconds" duration#
                             "Children" []}))))
       result#)))

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
     "position" "right"}))

(defn build-miniprofiler-response [response duration-ms profiler-id]
  (if (re-matches #".*</body>.*" (:body response))
    (assoc response :body (string/replace (:body response) #"</body>" (build-miniprofiler-script-tag duration-ms profiler-id)))
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
  (record "Thread/sleep"
          (Thread/sleep 100)
          (record "Thread/sleep"
                  (Thread/sleep 100)))
  (record "Thread/sleep"
          (Thread/sleep 100))
  "<body>lol</body>")

(defroutes app-routes
  (GET "/" [] (slow-fn)))

(def app
  (wrap-miniprofiler app-routes))
