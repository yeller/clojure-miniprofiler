(ns clojure-miniprofiler.example
  (:use compojure.core
        clojure-miniprofiler))

(defn another-slow []
  (custom-timing "sql" "query" "SELECT * FROM POSTS"
                 (Thread/sleep 50)))

(defn slow-fn []
  (trace "Thread/sleep1"
          (Thread/sleep 10)
         (another-slow)
          (trace "Thread/sleep2"
                 (custom-timing "sql" "query" "SELECT * FROM USERS"
                                (Thread/sleep 50))
                  (Thread/sleep 11)))
  (trace "Thread/sleep3"
         (another-slow)
          (Thread/sleep 12))
  "<body>lol</body>")

(defroutes app-routes
  (GET "/" [] (slow-fn)))

(defonce in-memory-store-instance (in-memory-store))

(def app
  (wrap-miniprofiler app-routes {:store in-memory-store-instance}))
