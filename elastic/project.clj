(defproject com.alliander.chainsaw/elastic "0.1.0"
  :author "Alliander NV <https://www.alliander.com/>"
  :description "Data driven logging library for taoensso/timbre"
  :url "https://github.com/aliander/chainsaw"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}

  :scm {:dir ".."}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.alliander.chainsaw/core "0.1.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [clojurewerkz/elastisch "3.0.0-beta2"]]

  :test-selectors {:default     (complement (some-fn :integration :loadtest))
                   :integration :integration
                   :loadtest    :loadtest
                   :all         (constantly true)}
  :profiles {:dev {:source-paths ["src" "../core/src"]}})
