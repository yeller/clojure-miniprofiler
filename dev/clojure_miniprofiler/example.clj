(ns clojure-miniprofiler.example
  (:require [compojure.core :refer [defroutes GET]]
            [clojure-miniprofiler.core :as core]))

(defn another-slow []
  (core/custom-timing "sql" "query" "SELECT * FROM POSTS"
    (do nil)))

(defn slow-fn []
  (Thread/sleep 10)
  (core/trace "foo"
    (Thread/sleep 10)
    (core/trace "foo1" (Thread/sleep 10))
    (core/trace "foo2" (Thread/sleep 20)))
  (core/trace "Thread/sleep1"
    (Thread/sleep 100)
    (do nil)
    (another-slow)
    (core/trace "Thread/sleep2"
      (core/custom-timing "sql" "query" "SELECT * FROM USERS"
        (do nil))
      (do nil)))
  (core/trace "Thread/sleep3"
    (another-slow)
    (do nil))
  "<head><script src=\"/foo.js\"></script></head><body>lol</body>")

(defroutes app-routes
  (GET "/" [] (slow-fn)))

(defonce in-memory-store-instance (core/in-memory-store))

(def app
  (core/wrap-miniprofiler app-routes {}))
