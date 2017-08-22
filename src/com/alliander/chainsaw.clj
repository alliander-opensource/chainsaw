;; Copyright 2017 [name of copyright owner]

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at

;; http://www.apache.org/licenses/LICENSE-2.0

;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns com.alliander.chainsaw
  "Logging utilities thingies."
  (:require [taoensso.encore :as encore]
            [taoensso.timbre :as log])
  (:import [java.util UUID]))

;;; Utilities

(defmacro with-merged-context
  "Merge the taoensso.timbre/*context* with the given context, instead
  of replacing it. It uses encore's `nested-merge`."
  [context & body]
  `(log/with-context (encore/nested-merge log/*context* ~context)
     ~@body))

(defn codify-throwable
  "Takes a throwable, and returns a codified map of its debug information."
  ;;Inspired by clojure.stacktrace
  [^Throwable tr]
  (when tr {:cause      (.getCause tr)
            :class      (.getName (class tr))
            :message    (.getMessage tr)
            :root-cause (.getCause tr)
            ;; :stacktrace (mapv str (.getStackTrace tr))
            :ex-data    (ex-data tr)}))

(defn bind-log-context
  "A pedestal helper ensuring the timbre *context*
  stays bound in the interceptor chain."
  [context]
  (update context :bindings #(merge % {#'log/*context* log/*context*})))

(defn min-levels
  "Returns a timbre middleware to filter messages for namespace by a
  minimum level. The `levels` is a sequence of namespace regexes
  followed by a level keyword."
  [& levels]
  (let [levels (partition 2 levels)]
    (fn min-levels [{:keys [?ns-str level] :as data}]
      (if ?ns-str
        (loop [levels levels]
          (if-let [[re min-level] (first levels)]
            (if (re-matches re ?ns-str)
              (when (log/level>= level min-level)
                data)
              (recur (rest levels)))
            data))
        data))))

;;; Data-driven logging.

(defn logd?
  "Returns truthy if the timbre appender-fn data was created using a
  data-driven log statement, such as logd or infod."
  [data]
  (::logd (:context data)))

(defn logd-middleware
  "Middleware for timbre adding :data-driven? to the timbre data,
  using the logd? function for the value."
  [data]
  (-> data
      (update :context dissoc ::logd)
      (assoc :data-driven? (logd? data))))

(defn ensure-logd-middleware!
  "Ensure the logd-middleware is added to the timbre config."
  []
  (log/swap-config! update :middleware #(let [added? (some #{#'logd-middleware} %)]
                                          (cond-> % (not added?) (conj #'logd-middleware)))))

(defmacro logd
  "Log a data-driven statement."
  {:argslist '([level          event {:parameter 'map1} {:parameter 'map2} ...]
               [level thowable event {:parameter 'map1} {:parameter 'map2} ...])}
  [level & args]
  {:pre [(contains? #{:trace :debug :info :warn :error :fatal :report} level)]}
  `(with-merged-context {:com.alliander.chainsaw/logd true}
     (log/log ~level ~@args)))

(defmacro traced  [& args] `(logd :trace  ~@args))
(defmacro debugd  [& args] `(logd :debug  ~@args))
(defmacro infod   [& args] `(logd :info   ~@args))
(defmacro warnd   [& args] `(logd :warn   ~@args))
(defmacro errord  [& args] `(logd :error  ~@args))
(defmacro fatald  [& args] `(logd :fatal  ~@args))
(defmacro reportd [& args] `(logd :report ~@args))

