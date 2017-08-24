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

(ns com.alliander.chainsaw.core-test
  (:require [clojure.string :as s]
            [clojure.test :refer [deftest is testing]]
            [com.alliander.chainsaw.core :as sut]
            [taoensso.timbre :as log]))

;; Testing definitions

(def arithmetic-exception
  (try (/ 1 0) (catch Exception e e)))


;; Test cases

(deftest with-merged-context-test
  (testing "Merging timbre context"
    (log/with-context {:root   1
                       :nested {:a 10 :b 10}}
      (sut/with-merged-context {:merged 2
                                :nested {:a 20 :b :swap/dissoc :c 20}}
        (is (= log/*context* {:root   1
                              :merged 2
                              :nested {:a 20 :c 20}}))))))

(deftest codify-throwable-test
  (testing "Provides output when a throwable is provided"
    (let [codified (sut/codify-throwable arithmetic-exception)]
      (is (map? codified))
      (is (= 5 (count (keys codified))))))
  (testing "Does return nil when no throwable is provided"
    (is (= nil (sut/codify-throwable nil)))))

(deftest min-levels-test
  (testing "Testing min-levels middleware"
    (let [min-levels-middleware (sut/min-levels #"info.level.namespace" :info)
          min-levels-appended   (atom [])
          min-levels-appender   {:enabled? true
                                 :fn       #(swap! min-levels-appended conj %)}]
      (log/with-config (assoc log/example-config
                              :middleware [min-levels-middleware]
                              :appenders {:min-levels min-levels-appender})
        (log/log! :debug nil ["logged"])
        (log/log! :debug nil ["not logged"] {:?ns-str "info.level.namespace"}))
      (is (= [["logged"]] (map :vargs @min-levels-appended))))))

(deftest logd-test
  (testing "Structured logging with logd"
    (let [logd-appended (atom [])
          logd-appender {:enabled? true
                         :fn       #(swap! logd-appended conj %)}]
      (log/with-config (assoc log/example-config
                              :appenders {:logd-test logd-appender})
        (sut/logd :info ::hi {:arg :val}))
      (is (every? sut/logd? @logd-appended)))))

(deftest logd-middleware-test
  (testing "Testing logd-middleware middleware"
    (let [logd-appended (atom [])
          logd-appender {:enabled? true
                         :fn       #(swap! logd-appended conj %)}]
      (log/with-config (assoc log/example-config
                              :middleware [sut/logd-middleware]
                              :appenders {:logd-test logd-appender})
        (sut/logd :info ::hi {:arg :val}))
      (is (every? :data-driven? @logd-appended)))))

(deftest logd-level-macros-test
  (testing "Testing log level macros"
    (let [logd-appended (atom [])
          logd-appender {:enabled? true
                         :fn       #(swap! logd-appended conj %)}]
      (log/with-config (assoc log/example-config
                              :level :trace
                              :appenders {:logd-test logd-appender})
        (sut/traced ::hi)
        (sut/debugd ::hi)
        (sut/infod ::hi)
        (sut/warnd ::hi)
        (sut/errord ::hi)
        (sut/fatald ::hi)
        (sut/reportd ::hi))
      (is (= [:trace :debug :info :warn :error :fatal :report]
             (map :level @logd-appended))))))
