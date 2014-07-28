(ns clojure-miniprofiler.bench
  (:require [criterium.core :refer [bench quick-bench]]
            [clojure-miniprofiler :refer [get-stacktrace-info wrap-miniprofiler]]
            [clojure-miniprofiler.store :as store]
            [clojure-miniprofiler.example :as example]))

(deftype NoopStore []
  store/Storage
  (store/save [_ _] nil)
  (store/fetch [_ _] nil))

(def noop-store (NoopStore.))

(def app (wrap-miniprofiler example/app-routes {:store noop-store}))

(defn -main [& args]
  (println "app-recording")
  (bench
    (app {:request-method :get :uri "/" :server-name "localhost"})))
