(defproject ua.kasta/clojure-miniprofiler "0.5.0-7"
  :description "a simple but effective profiler for clojure web applications"
  :resource-paths ["resources"]
  :url "https://github.com/piranha/clojure-miniprofiler"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [cheshire "5.10.1"]
                 [ring/ring-core "1.9.4"]
                 [fipp "0.6.24"]]
  :profiles {:dev   {:dependencies [[ring/ring "1.9.4"]
                                    [compojure "1.6.2"]]
                     :plugins      [[lein-ring "0.12.6"]]
                     :ring         {:handler clojure-miniprofiler.example/app}
                     :source-paths ["dev"]}
             :bench {:dependencies [[criterium "0.4.6"]
                                    [ring "1.9.4"]
                                    [compojure "1.6.2"]]
                     :main         clojure-miniprofiler.bench
                     :source-paths ["src" "bench" "dev"]
                     :jvm-opts     ^:replace
                     ["-Xms1024m" "-Xmx1024m" "-XX:+UseParNewGC" "-XX:+UseConcMarkSweepGC" "-server" "-XX:+CMSParallelRemarkEnabled" "-XX:+AggressiveOpts" "-XX:+UseFastAccessorMethods" "-Djava.awt.headless=true"]}})
