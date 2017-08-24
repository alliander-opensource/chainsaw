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

(ns com.alliander.chainsaw.elastic-test
  (:require [clojure.test :refer (deftest testing is)]
            [clojurewerkz.elastisch.native :as es]
            [clojurewerkz.elastisch.native.document :as esd]
            [clojurewerkz.elastisch.native.index :as esi]
            [taoensso.timbre :as log]
            [com.alliander.chainsaw.core :as logging]
            [com.alliander.chainsaw.elastic :as sut])
  (:import [java.util UUID]
           [java.util.concurrent CountDownLatch TimeUnit]))

;;; Tests without ElasticSearch

(deftest timbre->document-test
  (let [appended  (atom [])
        appender  {:enabled? true
                   :fn       #(swap! appended conj (sut/timbre->document %))}
        exception (ex-info "BOOM!" {})
        context   {:kont "ext"}]
    (log/with-config (assoc log/*config*
                            :middleware [logging/logd-middleware]
                            :appenders {:elastic appender})
      (log/with-context context
        (logging/infod ::hi {:my-param "hello"})
        (log/warn exception "bye")))
    (is (= [{:level      :info
             :namespace  "com.alliander.chainsaw.elastic-test"
             :error      nil
             :context    context
             :event      ::hi
             :parameters {:my-param "hello"}}
            {:level     :warn
             :namespace "com.alliander.chainsaw.elastic-test"
             :error     (logging/codify-throwable exception)
             :context   context
             :message   "bye"}]
           (map #(dissoc % :timestamp) @appended)))))


;;; Tests with ElasticSearch

(defn connect []
  (es/connect [["localhost" 9300]] {"cluster.name" "elasticsearch"}))

(deftest ^:integration elastic-appender-test
  (with-open [client (connect)]
    (let [index       (format "tcs-test-%s-logs" (rand-int Integer/MAX_VALUE))
          correlation (str (UUID/randomUUID))
          appended    (promise)
          appender    (sut/elastic-appender client index
                                            {:base-doc               {:base "root"}
                                             :flush-interval-seconds 1
                                             :bulk-listener-after    (fn [& _] (deliver appended true))})]

      (testing "Writing logs to ElasticSearch"
        (log/with-config (assoc log/*config*
                                :middleware [logging/logd-middleware]
                                :appenders {:elastic appender})
          (log/with-context {:hellodata-correlation correlation}
            (logging/infod ::hi {:my-param "hello"})
            (logging/warnd ::hello {:request-id "971429"})
            (log/error (ex-info "BOOM!" {:ex 'data}) "something bad happened!"))))

      (is (deref appended 2000 false))
      (esi/flush client (str index "-*"))

      (testing "Checking written logs from ElasticSearch"
        (let [indexed (esd/search client (str index "-*") "log")]
          (is (= [{:base       "root"
                   :level      "info"
                   :namespace  "com.alliander.chainsaw.elastic-test"
                   :error      {}
                   :context    {:hellodata-correlation correlation}
                   :event      "com.alliander.chainsaw.elastic-test/hi"
                   :parameters {:my-param "hello"}}

                  {:base       "root"
                   :level      "warn"
                   :namespace  "com.alliander.chainsaw.elastic-test"
                   :error      {}
                   :context    {:hellodata-correlation correlation}
                   :event      "com.alliander.chainsaw.elastic-test/hello"
                   :parameters {:request-id "971429"}}

                  {:base      "root"
                   :level     "error"
                   :namespace "com.alliander.chainsaw.elastic-test"
                   :error     {:cause      nil
                               :class      "clojure.lang.ExceptionInfo"
                               :message    "BOOM!"
                               :root-cause nil
                               :ex-data    {:ex "data"}}
                   :context   {:hellodata-correlation correlation}
                   :message   "something bad happened!"}]
                 (->> indexed :hits :hits
                      (map :_source)
                      (sort-by :timestamp)
                      (map #(dissoc % :timestamp))))))))))

(deftest ^:loadtest elastic-appender-load-test
  (with-open [client (connect)]
    (let [index       (format "tcs-test-%s-logs" (rand-int Integer/MAX_VALUE))
          correlation (str (UUID/randomUUID))
          iterations  10000
          written     (CountDownLatch. iterations)
          appender    (sut/elastic-appender client index {:base-doc {:app "chainsaw"}})
          appender-fn (:fn appender)
          wrapped     (assoc appender :fn (fn [data]
                                            (appender-fn data)
                                            (.countDown written)))]

      (testing "Writing logs to ElasticSearch"
        (time (log/with-config (assoc log/*config*
                                      :middleware [logging/logd-middleware]
                                      :appenders {:elastic wrapped})
                (log/with-context {:hellodata-correlation correlation}
                  (dotimes [i iterations]
                    (logging/infod ::load-start)
                    (logging/warnd ::load {:iteration i})
                    (logging/debugd ::load-end))))))

      (testing "Waiting for all logs to be written"
        (is (time (.await written 5 TimeUnit/SECONDS)))))))
