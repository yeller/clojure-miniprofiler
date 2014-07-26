(defproject clojure-miniprofiler "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :resource-paths ["ui"]
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure  "1.1.8"]
                 [cheshire  "5.3.1"]]
  :plugins [[lein-ring  "0.8.11"]]
  :ring {:handler clojure-miniprofiler.example/app})
