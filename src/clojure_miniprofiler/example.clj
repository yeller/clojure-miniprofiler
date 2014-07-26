(ns clojure-miniprofiler.example
  (:use compojure.core)
  (:require [clojure.string :as string]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            [ring.middleware.file-info :refer [file-info-response]]))

;; TODO
;; - regexes matching should be exact string comparison from the given miniprofiler path

(def miniprofiler-script-tag
  (slurp (:body (response/resource-response "include.partial.html"))))

(defn build-miniprofiler-script-tag []
  (reduce
    (fn [result [k v]]
      (string/replace result (re-pattern (str "\\{" k "\\}")) v))
    miniprofiler-script-tag
    {"path" "miniprofiler/"
     "version" "0.0.1"
     "currentId" "213213"}))

(defn build-miniprofiler-response [response _]
  (if (re-matches #".*</body>.*" (:body response))
    (assoc response :body (string/replace (:body response) #"</body>" (build-miniprofiler-script-tag)))
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

(defn miniprofiler-results-response [req]
  (println req)
  (println (slurp (:body req)))
  {:body "{}"}
  )

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
              response (handler req)
              t1 (System/nanoTime)
              duration-ms (float (/ (- t1 t0) 1000000))]
          (if (and
                (get-in response [:headers "Content-Type"])
                (re-matches #".*text/html.*" (get-in response [:headers "Content-Type"])))
            (build-miniprofiler-response response duration-ms)
            response))))))


(defn slow-fn []
  "<body>lol</body>")

(defroutes app-routes
  (GET "/" [] (slow-fn)))

(def app
  (wrap-miniprofiler app-routes))
