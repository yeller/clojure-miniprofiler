(ns clojure-miniprofiler
  (:require [clojure.string :as string]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            [ring.middleware.file-info :refer [file-info-response]]
            [cheshire.core :as json]
            [ring.middleware.params :as params]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.nested-params :as nested-params]
            [clojure-miniprofiler.types :refer :all]
            [clojure-miniprofiler.store :refer :all]
            fipp.edn))

;; storage here
(deftype InMemoryStore [store]
  Storage
  (save [_ profile]
    (swap! store assoc (get profile "Id") profile))
  (fetch [_ id]
    (get @store id)))

(defn in-memory-store
  "creates an in-memory miniprofiler results store"
  []
  (InMemoryStore.
    (atom {})))

(defn uuid
  "generates an uuid"
  []
  (str  (java.util.UUID/randomUUID)))

(defn distance-of-ns-time [ns0 ns1]
  (/ (float (- ns1 ns0)) 1000000))

(def ^:dynamic *current-miniprofiler* nil)

(defn current-ms []
  (System/currentTimeMillis))

(defn ms-since-start []
  (distance-of-ns-time (get @*current-miniprofiler* :start-ns) (System/nanoTime)))

(defn add-child [section-name]
  (->Timing (uuid) section-name (ms-since-start) nil [] {}))

(defmacro trace
  "trace lets you wrap sections of your code, so they show up in the miniprofiler UI.
   traces are nested (based on the call structure they wrap). A trace should be a section
   of your code, e.g. rendering a specific view,
                      loading the required data out of the database

  this macro just takes a name for the section you're wrapping in it."
  [section-name & body]
  `(if *current-miniprofiler*
     (let [parent-timer# (:current-timer @*current-miniprofiler*)
           new-timer# (add-child ~section-name)
           t0# (System/nanoTime)]
       (swap! *current-miniprofiler* assoc :current-timer new-timer#)
       (try
         (do ~@body)
         (finally
           (let [t1# (System/nanoTime)
                 duration# (distance-of-ns-time t0# t1#)]
             (swap! *current-miniprofiler*
                    (fn [current-miniprofiler#]
                      (assoc current-miniprofiler#
                             :current-timer
                             (assoc parent-timer#
                                    :children
                                    (conj (get parent-timer# :children)
                                          (assoc (:current-timer current-miniprofiler#)
                                                 :duration-ms duration#))))))))))
     (do ~@body)))

(defn html-pprint
  "pretty prints a string and returns it.
   Much faster than clojure.pprint, uses fipp"
  [x]
  (with-out-str (fipp.edn/pprint x)))

(defn create-custom-timing [execute-type command-string stacktrace-info]
  (->CustomTiming
    (uuid)
    execute-type
    (if (string? command-string) command-string (html-pprint command-string))
    stacktrace-info
    (ms-since-start)
    nil))

(defn get-stacktrace-info [duration]
  (if (< duration 5)
    ""
    (let [stacktrace-elems (.getStackTrace (Throwable.))]
      (->> stacktrace-elems
        (filter (fn [^StackTraceElement e]
                  (and
                    (not (re-matches #".*clojure_miniprofiler.*" (.getClassName e)))
                    (not (re-matches #".*clojure.lang.*" (.getClassName e)))
                    (not (re-matches #".*clojure.core.*" (.getClassName e))))))
        (drop 1)
        (take 5)
        (map (fn [^StackTraceElement e]
               (str (.getClassName e) "." (.getMethodName e) " " (.getFileName e) ":" (.getLineNumber e))))
        (string/join "\n")))))

(defn add-custom-timing [current-miniprofiler call-type custom-timing duration stacktrace-info]
  (assoc current-miniprofiler
         :current-timer
         (assoc (:current-timer current-miniprofiler)
                :custom-timings
                (assoc (get (:current-timer current-miniprofiler) :custom-timings {})
                       call-type
                       (conj (get-in current-miniprofiler [:current-timer :custom-timings call-type] [])
                             (assoc custom-timing
                                    :duration-ms duration
                                    :stacktrace-snippet stacktrace-info))))))

(defmacro custom-timing
  "wraps some of your code with a timing, so that it will show up
   in the miniprofiler results.
  takes 3 other arguments:
  call-type:
    the type of timed call this is. Examples might be \"sql\" or \"redis\"

  execute-type:
    within the call-type, what kind of request this is.
    Examples might be \"get\" or \"query\" or \"execute\"

  command-string:
    a pretty printed string of what this is executing.
    For SQL, this would be the query, for datomic the query or
    transaction data, for redis the key you're getting etc."
  [call-type execute-type command-string & body]
  `(if *current-miniprofiler*
    (let [custom-timing# (create-custom-timing ~execute-type ~command-string nil)
          t0# (System/nanoTime)]
      (try
        (do ~@body)
        (finally
          (let [t1# (System/nanoTime)
                duration# (distance-of-ns-time t0# t1#)
                stacktrace-info# (get-stacktrace-info duration#)]
            (swap! *current-miniprofiler*
                   add-custom-timing
                   ~call-type
                   custom-timing#
                   duration#
                   stacktrace-info#)))))
    (do ~@body)))

(defn create-miniprofiler [req initial-opts]
  (->Profiler
    (->Timing
      (uuid)
      "ring handler"
      0
      nil
      []
      {})
    (->Profile
      (uuid)
      (str (.toUpperCase (name (:request-method req))) " " (:uri req))
      (current-ms)
      0
      (:hostname initial-opts )
      nil
      [])
    (System/nanoTime)))

(defn reconstruct-profile [profiler duration]
  (assoc (:root profiler)
         :root
         (assoc
           (:current-timer profiler)
           :duration-ms
           duration)
         :duration-ms duration))

(defmacro with-recording [options req & body]
  `(let [miniprofiler# (create-miniprofiler ~req (or (:initial-opts ~options) (assert false)))]
     (binding [*current-miniprofiler* (atom miniprofiler#)]
     (let [t0# (System/nanoTime)
           result# (do ~@body)
           t1# (System/nanoTime)
           duration# (distance-of-ns-time t0# t1#)
           reconstructed-profile# (reconstruct-profile @*current-miniprofiler* duration#)]
       (save (:store ~options)
             (to-miniprofiler-map reconstructed-profile#))
       [(:id reconstructed-profile#) result#]))))

(def miniprofiler-script-tag
  (slurp (:body (response/resource-response "include.partial.html"))))

(defn build-miniprofiler-script-tag [duration-ms profiler-id options]
  (reduce
    (fn [result [k v]]
      (string/replace result (str "{" k "}") (str v)))
    miniprofiler-script-tag
    {"path" (str (:base-path options) "/")
     "version" "0.0.1"
     "currentId" profiler-id
     "ids" profiler-id
     "showControls" (str (:show-controls options true))
     "authorized" "true"
     "startHidden" "false"
     "position" (str (:position options "right"))
     "showTrivial" (str (:show-trivial options false))
     "showChildren" (str (:show-children options true))
     "maxTracesToShow" (str (:max-traces options 100))
     "trivialMilliseconds" (:trivial-ms options 2)}))

(defn build-miniprofiler-response
  "inserts the miniprofiler javascript tag into
   an html response."
  [response duration-ms profiler-id options]
  (assoc response
         :body
         (string/replace
           (:body response)
           #"</body>"
           (build-miniprofiler-script-tag duration-ms profiler-id options))))

(defn miniprofiler-resource-path [req options]
  (let [^String uri (:uri req)]
    (if (.contains uri (:base-path options))
      (do
        (if (.endsWith uri (str (:base-path options) "/includes.js"))
          "includes.js"
          (if (.endsWith uri (str (:base-path options) "/includes.tmpl"))
            "includes.tmpl"
            (if (.endsWith uri (str (:base-path options) "/includes.css"))
              "includes.css")))))))

(defn miniprofiler-results-request? [req options]
  (= (str (:base-path options) "/results") (:uri req)))

(defn get-id-from-req [req]
  (if (= (:request-method req) :post)
    (let [id (get-in req [:params "id"])]
      (if id
        id
        (get-in req [:params :id])))
    (string/replace (:query-string req) "id=" "")))

(defn render-share
  "returns a ring response for sharing the miniprofiler result"
  [id options]
  (let [resource (response/resource-response "share.html")
        result (fetch (:store options) id)]
    {:body
     (reduce
       (fn [result [k v]]
         (string/replace result (re-pattern (str "\\{" k "\\}")) (str v)))
       (slurp (:body resource))
       {"name" (get result "Name")
        "duration" (get result "DurationMilliseconds")
        "json" (json/generate-string result)
        "includes" (build-miniprofiler-script-tag (get result "DurationMilliseconds") (get result "Id") options)})}))

(defn add-client-results
  "adds incoming client results to a stored set of results"
  [req stored-results]
  (let [timings (into {} (map (fn [[k v]] [k (Long/parseLong v)]) (get-in req [:params :clientPerformance :timing])))
        request-start (get timings :requestStart)
        timingsDiffSinceStart (into {} (map (fn [[k v]] [k (- v request-start)]) timings))]
   (assoc
     stored-results
     "ClientTimings"
     {"RedirectCount"
      (get-in req [:params :clientPerformance :navigation :redirectCount])
      "Timings"
      (sort-by
        (fn [a] (get a "Start"))
        (filter map?
                [{"Name" "Connect"
                  "Start" (get timingsDiffSinceStart :connectStart)
                  "Duration" (- (get timingsDiffSinceStart :connectEnd 0) (get timingsDiffSinceStart :connectStart 0))}

                 {"Name" "Domain Lookup"
                  "Start" (get timingsDiffSinceStart :domainLookupStart)
                  "Duration" (- (get timingsDiffSinceStart :domainLookupEnd 0) (get timingsDiffSinceStart :domainLookupStart 0))}

                 {"Name" "Dom Content Loaded"
                  "Start" (get timingsDiffSinceStart :domContentLoadedEventStart)
                  "Duration" (- (get timingsDiffSinceStart :domContentLoadedEventEnd 0) (get timingsDiffSinceStart :domContentLoadedEventStart 0))}

                 {"Name" "Loading"
                  "Start" (get timingsDiffSinceStart :loadEventStart)
                  "Duration" (- (get timingsDiffSinceStart :loadEventEnd 0) (get timingsDiffSinceStart :loadEventStart 0))}

                 {"Name" "Unloading"
                  "Start" (get timingsDiffSinceStart :unloadEventStart)
                  "Duration" (- (get timingsDiffSinceStart :unloadEventEnd 0) (get timingsDiffSinceStart :unloadEventStart 0))}

                 {"Name" "Response"
                  "Start" (get timingsDiffSinceStart :responseStart)
                  "Duration" (- (get timingsDiffSinceStart :responseEnd 0) (get timingsDiffSinceStart :responseStart 0))}

                 {"Name" "Start Fetch"
                  "Start" (get timingsDiffSinceStart :fetchStart)
                  "Duration" 0}

                 (if (not (= 0 (get timings :secureConnectionStart)))
                   {"Name" "secureConnectionStart"
                    "Start" (get timingsDiffSinceStart :secureConnectionStart)
                    "Duration" 0})

                 {"Name" "Dom Interactive"
                  "Start" (get timingsDiffSinceStart :domInteractive)
                  "Duration" 0}

                 {"Name" "Navigation Start"
                  "Start" (get timingsDiffSinceStart :navigationStart)
                  "Duration" 0}

                 {"Name" "First Paint Time"
                  "Start" (get timingsDiffSinceStart "First Paint Time")
                  "Duration" 0}

                 {"Name" "First Paint After Load Time"
                  "Start" (get timingsDiffSinceStart "First Paint After Load Time")
                  "Duration" 0}]))})))

(defn miniprofiler-results-response
  "builds a ring response that returns miniprofiler results
   as json in the :body."
  [req options]
  (let [with-params (params/params-request req)]
    (let [id (get-id-from-req with-params)]
      (if (= (:request-method with-params) :post)
        (let [nested (keyword-params/keyword-params-request (nested-params/nested-params-request with-params))]
          {:body (json/generate-string (add-client-results nested (fetch (:store options) id)))})
        (render-share id options)))))

(defn default-options [opts]
  (merge
    {:base-path "/miniprofiler"
     :authorized? (fn [req] (= (:server-name req) "localhost"))
     :trivial-ms 2
     :show-trivial false
     :position "right"
     :max-traces 100
     :show-children true
     :show-controls true
     :initial-opts
     {:hostname (.getHostName (java.net.InetAddress/getLocalHost))}}
    opts))

(defn assets-request?
  "denotes if a request is for assets"
  [req]
  (let [^String uri (:uri req)]
    (or (.endsWith uri ".js")
        (.endsWith uri ".css"))))

(defn profile-request?
  "dictates whether this request should be profiled
   (after it's been authenticated). By default
   removes profiling of assets."
  [req options]
  (if-let [custom-profile-request? (:profile-request? options)]
    (custom-profile-request? req)
    (not (assets-request? req))))

(defn respond-with-asset
  "returns a miniprofiler asset as a ring response"
  [req miniprofiler-resource-path]
  (->
    (response/resource-response miniprofiler-resource-path)
    (file-info-response req)
    (content-type-response req)))

(defn run-handler-profiled
  "runs a handler in profiled mode, potentially adding the profiler
   into the content, stores traces etc."
  [req handler options]
  (let [t0 (System/nanoTime)
        [profile-id response] (with-recording options req (handler req))
        t1 (System/nanoTime)
        duration-ms (distance-of-ns-time t0 t1)]

    (if (and (get-in response [:headers "Content-Type"])
             (re-matches #".*text/html.*" (get-in response [:headers "Content-Type"])))
      (build-miniprofiler-response response duration-ms profile-id options)
      response)))

(defn wrap-miniprofiler
  "Ring middleware for using miniprofiler.
   Takes an options map with the following options:

   :authorized? (a function, optional):
    the most important option - this specifies which
    requests will be profiled. By default only requests
    from localhost will be, but in production you probably
    want to turn this on for admins/etc.

  :base-path (a string, optional):
    a string that denotes the paths miniprofiler will interact
    on. By default set to /miniprofiler.

  :trivial-ms (an integer, measured in milliseconds):
    traces that take below this amount of time are hidden by default
    in the web ui.

  :profile-request? (a function)
    a function that dictates whether we should profile the current request.
    By default asset requests aren't profiled, just html/json ones."
  [handler opts]
  (let [options (map->Options (default-options opts))
        authorized? (:authorized? options)]
    (fn [req]
      (if (authorized? req)
        (if-let [miniprofiler-asset-path (miniprofiler-resource-path req options)]
          (respond-with-asset req miniprofiler-asset-path)

          (if (miniprofiler-results-request? req options)
            (miniprofiler-results-response req options)
            (if (profile-request? req options)
              (run-handler-profiled req handler options)
              (handler req))))
        (handler req)))))
