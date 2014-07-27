(defproject clojure-miniprofiler "0.1.1-SNAPSHOT"
  :description "FIXME: write description"
  :resource-paths ["resources"]
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire  "5.3.1"]]
  :profiles {:dev {:dependencies [[compojure "1.1.8"]]
                   :plugins [[lein-ring "0.8.11"]]
                   :ring {:handler clojure-miniprofiler.example/app}}})
