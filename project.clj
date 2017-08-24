(defproject com.alliander/chainsaw "0.1.0"
  :author "Alliander NV <https://www.alliander.com/>"
  :description "Data driven logging library for taoensso/timbre"
  :url "https://github.com/aliander/chainsaw"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}

  :dependencies [[com.alliander.chainsaw/core "0.1.0"]
                 [com.alliander.chainsaw/elastic "0.1.0"]]

  :plugins [[lein-sub "0.3.0"]]
  :sub ["core" "elastic"])

