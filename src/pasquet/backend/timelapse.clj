(ns pasquet.backend.timelapse
  (:require [clojure.string :as str]))

(defn parse-cameras [cameras-str]
  (when cameras-str
    (mapv (fn [entry]
            (let [[name id] (str/split entry #":")]
              {:name name :id id}))
          (str/split cameras-str #","))))

(defn capture-interval-seconds [fps day-duration]
  (/ 86400 (* fps day-duration)))

(defn frame-dir [frames-path camera-name date-str]
  (str frames-path "/" camera-name "/" date-str))

(defn frame-path [frames-path camera-name date-str timestamp]
  (str (frame-dir frames-path camera-name date-str) "/frame-" timestamp ".jpg"))

(defn daily-video-path [videos-path camera-name date-str]
  (str videos-path "/daily/" camera-name "/" date-str ".mp4"))

(defn monthly-video-path [videos-path camera-name year-month]
  (str videos-path "/monthly/" camera-name "/" year-month ".mp4"))

(defn yearly-video-path [videos-path camera-name year]
  (str videos-path "/yearly/" camera-name "/" year ".mp4"))
