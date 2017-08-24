;; Copyright 2017 Alliander NV

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at

;; http://www.apache.org/licenses/LICENSE-2.0

;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns com.alliander.chainsaw.elastic
  "A daily timbre appender for elasticsearch."
  (:require [clojure.string :as string]
            [clojurewerkz.elastisch.native.conversion :as conversion]
            [clojurewerkz.elastisch.native.index :as index]
            [taoensso.timbre :as timbre]
            [com.alliander.chainsaw.core :as logging])
  (:import [java.text SimpleDateFormat]
           [java.util Date]
           [java.util.concurrent TimeUnit]
           [org.elasticsearch.action.bulk BulkProcessor BulkProcessor$Listener BulkRequest
            BulkResponse]
           [org.elasticsearch.common.unit TimeValue]))

;;; Internals

(def ^:private day-formatter (SimpleDateFormat. "yyyyMMdd"))

(defn ^:no-doc daily-index-name
  "Returns an index name in the form of
  \"<`index-name-prefix`>-<yyyymmdd-of-`date`>\"."
  [index-name-prefix ^Date date]
  {:pre [(string? index-name-prefix)
         (instance? Date date)]}
  (str index-name-prefix "-" (.format day-formatter date)))

(defn ^:no-doc timbre->document
  "Transforms a timbre data map to a elasticsearch document. The
  document will at least have the following propertiies:

  :level - the log level of the logging statement.

  :namespace - the namespace of the logging statement.

  :timestamp - the milliseconds since epoch at the time of the logging
    statement.

  :context - the timbre context at the time of logging.

  :error - a JSONified representation of the logged throwable, if
    applicable.

  The other properties depend on whether the `logd` macros were used
  and the `logd-middleware` from the `com.alliander.chainsaw.core`
  namespace is active. If so, the document contains the following
  properties:

  :event - the first (non-throwable) argument of the logging statement.

  :parameters - a merger of the rest of the arguments to the logging
    statement, which are expected to be maps.

  Otherwise, the document contains the following:

  :message - the default log string."
  [{:keys [level ?ns-str ?err instant context msg_ vargs_ data-driven?]}]
  (let [base {:level     level
              :namespace ?ns-str
              :error     (logging/codify-throwable ?err)
              :timestamp (.getTime instant)
              :context   context}]
    (if data-driven?
      (let [vargs  (force vargs_)
            event  (first vargs)
            params (apply merge {} (rest vargs))]
        (merge base {:event      event
                     :parameters params}))
      (let [message (force msg_)]
        (merge base {:message message})))))

(defn ^:no-doc ensure-template
  "Puts a mapping template for the `<index-name-prefix>-*` indices,
  based on the content in `timbre->document` transformation. The given
  mapping is merged in."
  [es-client index-name-prefix mapping]
  (index/put-template es-client "elastic-appender"
                      {:template (str index-name-prefix "-*")
                       :mappings {:log {:properties (merge mapping
                                                           {:timestamp {:type "date"}
                                                            :event     {:type  "string"
                                                                        :index "not_analyzed"}
                                                            :error     {:properties {}}
                                                            :context   {:properties {}}})}}}))

(defn ^:no-doc blacklisted?
  "Function that returns true when the appender should filter out
  noisy (elasticsearch) logging, which could otherwise result in this
  appender DDoS'ing itself."
  [{:keys [?ns-str]}]
  (when ?ns-str
    (or (string/starts-with? ?ns-str "org.elasticsearch")
        (string/starts-with? ?ns-str "com.alliander.chainsaw.elastic"))))

(defn ^:no-doc elastic-appender-state-fns
  "Creates state transition and test functions for the elastic-appender-fn."
  ([] (elastic-appender-state-fns nil))
  ([{:keys [millis-per-try max-millis]
     :or   {millis-per-try 2000
            max-millis     30000}}]
   (let [state (atom nil)]
     {:initialized? #(:done @state)
      :initialized! #(reset! state {:done true})
      :back-off?    #(when-let [bo (:back-off @state)]
                       (< (System/currentTimeMillis) bo))
      :back-off!    #(let [tries  (:tries @state 1)
                           millis (min (* tries millis-per-try) max-millis)]
                       (reset! state {:back-off (+ (System/currentTimeMillis) millis)
                                      :tries    (inc tries)})
                       millis)})))

(defn ^:no-doc elastic-appender-fn
  "Creates a timbre appender function, using the bulk-processor for
  index requests in the `daily-index-name` index prefixed with the
  given index-name-prefix. The indexed document is a merger between
  the optional base-doc and the result of `timbre->document`.

  The appender also tries to call `ensure-template` above, using the
  given es-client."
  [es-client ^BulkProcessor bulk-processor index-name-prefix
   {:keys [mapping base-doc data-driven-only?]}]
  (let [{:keys [initialized? initialized! back-off? back-off!]} (elastic-appender-state-fns)]
    (fn [{:keys [?ns-str ?file ?line data-driven?] :as data}]
      (when-not (blacklisted? data)
        (when (or data-driven? (not data-driven-only?))

          (if (initialized?)
            (try
              (let [index-name    (daily-index-name index-name-prefix (:instant data))
                    document      (merge base-doc (timbre->document data))
                    index-request (conversion/->index-request index-name "log" document)]
                (.add bulk-processor index-request))
              (catch Exception e
                (timbre/warnf "failed to create/add index request for logging statement at %s:%s - %s"
                              (or ?file ?ns-str) (or ?line "??") (.getMessage e))))

            (try
              (when-not (back-off?)
                (timbre/debug "initializing elasticsearch logging template for index"
                              (str index-name-prefix "-*"))
                (ensure-template es-client index-name-prefix mapping)
                (initialized!))
              (catch Exception e
                (let [millis (back-off!)]
                  (timbre/warnf (str "initializing elasticsearch logging template for index %s failed "
                                     "(backing off for %s milliseconds) - %s")
                                (str index-name-prefix "-*") millis (.getMessage e)))))))))))

(defn ^:no-doc bulk-processor-listener
  "Create a BulkProcessor.Listener implementation, given the (all
  optional) listener functions. The functions have the same parameter
  lists as in the Listener interface."
  [{:keys [before after failed]}]
  (reify BulkProcessor$Listener
    (^void beforeBulk [this ^long execution-id ^BulkRequest request]
     (when before
       (before execution-id request)))
    (^void afterBulk [this ^long execution-id ^BulkRequest request ^BulkResponse response]
     (when after
       (after execution-id request response)))
    (^void afterBulk [this ^long execution-id ^BulkRequest request ^Throwable t]
     (when failed
       (failed execution-id request t)))))

(defn bulk-after-debugger
  "A simple bulk-listener-after debugger."
  [id request response]
  (timbre/debug "bulk processed: " id (conversion/bulk-response->map response)))

(defn bulk-failed-warner
  "A simple bulk-listener-failed warner."
  [id request throwable]
  (timbre/warn throwable "bulk failed: " id))


;;; Public library API

(defn elastic-appender
  "Create an elasticsearch appender using the given native
  elasticsearch client. It will index \"log\" documents in the daily
  index \"<`index-name-prefix`>-yyyymmdd\". Each document will have at
  least the contents of the given `base-doc`.

  This appender benefits from the `logd-middleware` and the `logd`
  macros from the `com.alliander.chainsaw.core` namespace for structured
  logging. For details on how this influences the indexed document,
  see the `timbre->document` function.

  The appender uses an elasticsearch BulkProcessor, in order to log
  efficiently. This BulkProcessor can be tuned using the options
  below. The returned appender map holds the created BulkProcessor,
  under the :bulk key. This can be used to close the BulkProcessor in
  order to clean up resources.

  One can supply an optional options map. The supported options are:

  :base-doc - the base document with properties that will always be
    indexed, such as an app-name.

  :flush-interval-seconds - the maximum number of seconds to wait
    before forcing a bulk operation, default is 5.

  :async? - a boolean indicating whether the timbre appender should be
    async or not, default is false.

  :min-level - the initial minimum level of the appender, default is
    nil, meaning it uses the global log level.

  :bulk-listener-before, :bulk-listener-after, :bulk-listener-failed -
    callback functions corresponding to the BulkProcessor.Listener
    interface.

  :mapping - extra mappings for the \"log\" documents in
    elasticsearch.

  :data-driven-only? - a boolean indicating whether the timbre appender
    should only index log statements that are data-driven, default is
    false."
  ([es-client index-name-prefix]
   (elastic-appender es-client index-name-prefix {}))
  ([es-client index-name-prefix
    {:keys [bulk-listener-before bulk-listener-after bulk-listener-failed
            flush-interval-seconds async? min-level]
     :or   {flush-interval-seconds 5
            async?                 false}
     :as   opts}]
   (let [bulk-listener  (bulk-processor-listener {:before bulk-listener-before
                                                  :after  bulk-listener-after
                                                  :failed bulk-listener-failed})
         bulk-processor (.. (BulkProcessor/builder es-client bulk-listener)
                            (setFlushInterval (TimeValue/timeValueSeconds flush-interval-seconds))
                            (build))
         appender-fn    (elastic-appender-fn es-client bulk-processor index-name-prefix opts)]
     ;;---TODO Unsure whether it should be async or not. If set to true, throughput in code is faster
     ;;        (like 12k log messages per second), but might clog agent threadpool. If set to false,
     ;;        throughput is lower (like 4k per second), but won't clog agent threadpool and may be
     ;;        more predictable. These tests were done locally, using elastic-appender-load-test. [AR]
     ;;---TODO Another option would be to use our own threadpool, but this would mean implementing
     ;;        the asynchronousy ourselves, as the timbre library uses the default threadpool for
     ;;        its agents. [NR]
     {:enabled?  true
      :async?    async?
      :min-level min-level
      :fn        appender-fn
      :bulk      bulk-processor})))

(defn add-elastic-appender!
  "Add the daily elastic appender to the timbre config, see
  `elastic-appender` for details. This also ensures the
  `logd-middleware` is active."
  ([es-client index-name-prefix]
   (add-elastic-appender! es-client index-name-prefix {}))
  ([es-client index-name-prefix opts]
   (let [appender (elastic-appender es-client index-name-prefix opts)]
     (logging/ensure-logd-middleware!)
     (timbre/swap-config! assoc-in [:appenders :elastic] appender))))

(defn set-elastic-appender-level!
  "Set the minimal log level for the elastic appender. Default is nil,
  meaning it uses the global log level."
  [level]
  (timbre/swap-config! assoc-in [:appenders :elastic :min-level] level))

(defn remove-elastic-appender!
  "Remove the daily elastic appender from the timbre config. It also
  closes the elasticsearch BulkProcessor used by the appender. One can
  optionally supply an options map, with the following options:

  :await-close-secs - the number of seconds to wait for the
    BulkProcessor to close, default is 10."
  ([] (remove-elastic-appender! nil))
  ([{:keys [await-close-secs] :or {await-close-secs 10}}]
   (when-let [bulk-processor (get-in timbre/*config* [:appenders :elastic :bulk])]
     (.awaitClose bulk-processor await-close-secs TimeUnit/SECONDS))
   (timbre/swap-config! update :appenders dissoc :elastic)))
