(defproject com.alliander.chainsaw/elastic "0.1.0"
  :scm {:dir ".."}
  :source-paths ["src" "../core/src"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [clojurewerkz/elastisch "3.0.0-beta2"]]

  :test-selectors {:default     (complement (some-fn :integration :loadtest))
                   :integration :integration
                   :loadtest    :loadtest
                   :all         (constantly true)})
