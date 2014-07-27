(ns clojure-miniprofiler.example
  (:use compojure.core
        clojure-miniprofiler))

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
