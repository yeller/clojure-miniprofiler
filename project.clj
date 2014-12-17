(defproject clojure-miniprofiler "0.2.4"
  :description "FIXME: write description"
  :resource-paths ["resources"]
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire  "5.3.1"]]
  :profiles {:dev {:dependencies [[compojure "1.1.8"]]
                   :plugins [[lein-ring "0.8.11"]]
                   :ring {:handler clojure-miniprofiler.example/app}
                   :source-paths ["dev"]}
             :bench {:dependencies [[criterium "0.4.1"]
                                    [ring "1.2.0"]
                                    [compojure "1.1.8"]]
                     :main clojure-miniprofiler.bench
                     :source-paths ["src" "bench" "dev"]
                     :jvm-opts ^:replace
                     ["-Xms1024m" "-Xmx1024m" "-XX:+UseParNewGC" "-XX:+UseConcMarkSweepGC" "-server" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-Djava.awt.headless=true"]}})
