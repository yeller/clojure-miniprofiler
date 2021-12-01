(ns clojure-miniprofiler.core
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            [ring.middleware.file-info :refer [file-info-response]]
            [cheshire.core :as json]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.nested-params :as nested-params]
            fipp.edn

            [clojure-miniprofiler.types :as types]
            [clojure-miniprofiler.store :as store]))


(def ^:dynamic *current-miniprofiler* nil)

;; storage here
(deftype InMemoryStore [store]
  store/Storage
  (save [_ profile]
    (swap! store assoc (get profile "Id") profile))
  (fetch [_ id]
    (get @store id)))


(defn in-memory-store
  "creates an in-memory miniprofiler results store"
  []
  (InMemoryStore. (atom {})))


(defn uuid
  "generates an uuid"
  []
  (str  (java.util.UUID/randomUUID)))


(defn distance-of-ns-time [ns0 ns1]
  (/ (float (- ns1 ns0)) 1000000))


(defn current-ms []
  (System/currentTimeMillis))


(defn ms-since-start []
  (distance-of-ns-time (get @*current-miniprofiler* :start-ns) (System/nanoTime)))


(defn add-child [section-name]
  (types/map->Timing
    {:id             (uuid)
     :timing-name    section-name
     :start-ms       (ms-since-start)
     :duration-ms    nil
     :children       []
     :custom-timings {}}))


(defmacro trace
  "trace lets you wrap sections of your code, so they show up in the miniprofiler UI.
  traces are nested (based on the call structure they wrap). A trace should be a
  section of your code, e.g. rendering a specific view, loading the required
  data out of the database, etc.

  This macro just takes a name for the section you're wrapping in it."
  [section-name & body]
  `(if *current-miniprofiler*
     (let [parent-timer# (:current-timer @*current-miniprofiler*)
           new-timer#    (add-child ~section-name)
           t0#           (System/nanoTime)]
       (swap! *current-miniprofiler* assoc :current-timer new-timer#)
       (try
         (do ~@body)
         (finally
           (let [t1#       (System/nanoTime)
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
  (types/map->CustomTiming
    {:id                 (uuid)
     :execute-type       execute-type
     :command-string     (if (string? command-string)
                           command-string
                           (html-pprint command-string))
     :stacktrace-snippet stacktrace-info
     :start-ms           (ms-since-start)
     :duration-ms        nil}))


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
  (let [profile-name (str (.toUpperCase (name (:request-method req))) " " (:uri req))]
    (types/map->Profiler
      {:current-timer (types/map->Timing
                        {:id             (uuid)
                         :timing-name    "ring handler"
                         :start-ms       0
                         :duration-ms    nil
                         :children       []
                         :custom-timings {}})
       :root          (types/map->Profile
                        {:id             (uuid)
                         :profile-name   profile-name
                         :started        (current-ms)
                         :duration-ms    0
                         :machine-name   (:hostname initial-opts)
                         :root           nil
                         :client-timings []})
       :start-ns      (System/nanoTime)})))


(defn reconstruct-profile [profiler duration]
  (assoc (:root profiler)
    :root        (assoc (:current-timer profiler) :duration-ms duration)
    :duration-ms duration))


(defmacro with-recording [options req & body]
  `(let [miniprofiler# (create-miniprofiler ~req (or (:initial-opts ~options) (assert false)))]
     (binding [*current-miniprofiler* (atom miniprofiler#)]
       (let [t0#                    (System/nanoTime)
             result#                (do ~@body)
             t1#                    (System/nanoTime)
             duration#              (distance-of-ns-time t0# t1#)
             reconstructed-profile# (reconstruct-profile @*current-miniprofiler* duration#)]
         (store/save (:store ~options)
           (types/to-miniprofiler-map reconstructed-profile#))
         [(:id reconstructed-profile#) result#]))))


(defn parse-json [value]
  (json/parse-string
    (if (string? value) value (slurp value))))


(defn build-miniprofiler-response-json
  "inserts the miniprofiler details into a json
  response."
  [response duration-ms profiler-id options]
  (if (or (get-in response [:headers "Content-Length"])
          (string? (:body response)))
    (let [body (-> (:body response)
                   (parse-json)
                   (assoc :miniprofiler
                     {:id profiler-id
                      :link (str (:base-path options) "/results?id=" profiler-id)
                      :durationMilliseconds duration-ms})
                   (json/generate-string))
          body*  (.getBytes body "UTF-8")
          length (count body*)]
      (-> response
          (assoc :body (io/input-stream body*))
          (update :headers assoc
            "Content-Length"     (str length)
            "X-MiniProfiler-Ids" (json/generate-string [profiler-id]))))
    response))


(def miniprofiler-script-tag
  (slurp (:body (response/resource-response "include.partial.html"))))


(defn build-miniprofiler-script-tag [duration-ms profiler-id options]
  (reduce
    (fn [result [k v]]
      (string/replace result (str "{" k "}") (string/re-quote-replacement (str v))))
    miniprofiler-script-tag
    {"path"                (str (:base-path options) "/")
     "version"             "0.0.1"
     "currentId"           profiler-id
     "ids"                 profiler-id
     "showControls"        (str (:show-controls options true))
     "authorized"          "true"
     "startHidden"         "false"
     "position"            (str (:position options "right"))
     "showTrivial"         (str (:show-trivial options false))
     "showChildren"        (str (:show-children options true))
     "maxTracesToShow"     (str (:max-traces options 100))
     "trivialMilliseconds" (:trivial-ms options 2)}))


(defn build-miniprofiler-response-html
  "inserts the miniprofiler javascript tag into
   an html response."
  [response duration-ms profiler-id options]
  (let [body       (:body response)
        insert-at  (.lastIndexOf body "</body>")
        script-tag (build-miniprofiler-script-tag duration-ms profiler-id options)
        new-body   (if (pos? insert-at)
                     (str (.substring body 0 insert-at)
                       script-tag
                       (.substring body insert-at))
                     body)]
    (-> response
        (assoc :body new-body)
        (update :headers assoc
          "X-MiniProfiler-Ids" (json/generate-string [profiler-id])))))


(defn miniprofiler-resource-path [req options]
  (let [^String uri (:uri req)]
    (when (.startsWith uri (:base-path options))
      (cond
        (.endsWith uri (str (:base-path options) "/includes.js"))   "includes.js"
        (.endsWith uri (str (:base-path options) "/includes.tmpl")) "includes.tmpl"
        (.endsWith uri (str (:base-path options) "/includes.css"))  "includes.css"))))


(defn miniprofiler-results-request? [req options]
  (= (str (:base-path options) "/results")
     (:uri req)))


(defn req->id [req]
  (if (= (:request-method req) :post)
    (let [params (:params req)]
      (or (get params "id")
          (:id params)))
    (string/replace (:query-string req) "id=" "")))


(defn render-share
  "returns a ring response for sharing the miniprofiler result"
  [id options]
  (let [resource (response/resource-response "share.html")
        result   (store/fetch (:store options) id)]
    {:body
     (reduce
       (fn [result [k v]]
         (string/replace result (re-pattern (str "\\{" k "\\}")) (string/re-quote-replacement (str v))))
       (slurp (:body resource))
       {"name"     (get result "Name")
        "duration" (get result "DurationMilliseconds")
        "json"     (json/generate-string result)
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

                 (when (not (= 0 (get timings :secureConnectionStart)))
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
  (let [with-params (params/params-request req)
        id          (req->id with-params)]
    (if (= (:request-method with-params) :post)
      (let [nested (-> with-params
                       nested-params/nested-params-request
                       keyword-params/keyword-params-request)
            result (add-client-results nested (store/fetch (:store options) id))]
        {:headers {"content-type" "application/json"}
         :body    (json/generate-string result)})
      (render-share id options))))


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
     :store (in-memory-store)
     :initial-opts
     {:hostname (.getHostName (java.net.InetAddress/getLocalHost))}}
    opts))

(defn assets-request?
  "denotes if a request is for assets"
  [req]
  (let [^String uri (:uri req)]
    (or (.endsWith uri ".ico")
        (.endsWith uri ".js")
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

    (if-let [ctype (get-in response [:headers "Content-Type"])]
      (condp re-matches ctype
        #".*text/html.*" (build-miniprofiler-response-html response duration-ms profile-id options)
        #".*application/json.*" (build-miniprofiler-response-json response duration-ms profile-id options)
        response)
      response)))

(defn wrap-miniprofiler
  "Ring middleware for using miniprofiler.
   Takes an options map with the following options:

  :store (optional, something that implements clojure-miniprofiler.store/Storage)
    Implementation of a store for miniprofiler results. By default
    uses an in-memory store, which means that profiling won't work properly
    in a multi-machine environment. See the documentation on the Storage
    protocol for more.

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
  (let [options     (types/map->Options (default-options opts))
        authorized? (:authorized? options)]
    (fn [req]
      (cond
        (not (authorized? req))
        (handler req)

        (miniprofiler-resource-path req options)
        (respond-with-asset req (miniprofiler-resource-path req options))

        (miniprofiler-results-request? req options)
        (miniprofiler-results-response req options)

        (profile-request? req options)
        (run-handler-profiled req handler options)

        :else
        (handler req)))))
