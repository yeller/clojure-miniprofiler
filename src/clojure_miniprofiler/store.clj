(ns clojure-miniprofiler.store)

(defprotocol Storage
  (save [this profile])
  (fetch [this id]))
