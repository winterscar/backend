(ns tasks
  (:require [com.biffweb.tasks :as tasks]))

(defn hello
  "Says 'Hello'"
  []
  (println "Hello"))

(def custom-tasks
  {"hello" #'hello})

(def tasks (merge tasks/tasks custom-tasks))
