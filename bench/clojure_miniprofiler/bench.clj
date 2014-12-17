(ns clojure-miniprofiler.bench
  (:require [criterium.core :refer [bench quick-bench]]
            [clojure-miniprofiler :refer [get-stacktrace-info wrap-miniprofiler]]
            [clojure-miniprofiler.store :as store]
            [clojure-miniprofiler.example :as example]
            clojure.pprint
            fipp.edn))

(deftype NoopStore []
  store/Storage
  (store/save [_ _] nil)
  (store/fetch [_ _] nil))

(def noop-store (NoopStore.))

(def app (wrap-miniprofiler example/app-routes {:store noop-store}))

(def sample-query
  '[:find ?title
    :where
    [?m :movie/title ?title]
    [?m :movie/year ?year]
    [(< ?year 1984)]])

(defn -main [& args]
  (println "app-no-recording")
  (println "=========================\n\n")
  (quick-bench
    (app {:request-method :get :uri "/" :server-name "some-server"}))

  (println "app-with-recording")
  (println "=========================\n\n")
  (let [always-authorized (wrap-miniprofiler example/app-routes
                                             {:store noop-store
                                              :authorized? (fn [_] true)})]
    (quick-bench (always-authorized {:request-method :get :uri "/" :server-name "localhost"})))

  (println "html-pprint-via-clojure-pprint")
  (println "=========================\n\n")
  (quick-bench (with-out-str (clojure.pprint/pprint sample-query)))


  (println "html-pprint-via-fipp-edn")
  (println "=========================\n\n")
  (quick-bench (with-out-str (fipp.edn/pprint sample-query))))
