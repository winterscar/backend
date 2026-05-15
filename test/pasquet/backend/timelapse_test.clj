(ns pasquet.backend.timelapse-test
  (:require [clojure.test :refer [deftest is testing]]
            [pasquet.backend.timelapse :as tl]))

(deftest parse-cameras-test
  (testing "parses comma-separated name:id pairs"
    (is (= [{:name "front-door" :id "abc123"}
            {:name "backyard" :id "def456"}]
           (tl/parse-cameras "front-door:abc123,backyard:def456"))))
  (testing "single camera"
    (is (= [{:name "cam" :id "id1"}]
           (tl/parse-cameras "cam:id1"))))
  (testing "nil input"
    (is (nil? (tl/parse-cameras nil)))))

(deftest capture-interval-seconds-test
  (testing "30fps 30s day = 96s interval"
    (is (= 96 (tl/capture-interval-seconds 30 30))))
  (testing "24fps 60s day = 60s interval"
    (is (= 60 (tl/capture-interval-seconds 24 60)))))

(deftest path-functions-test
  (let [frames "/tmp/frames"
        videos "/tmp/videos"]
    (testing "frame-dir"
      (is (= "/tmp/frames/front/2026-05-15"
             (tl/frame-dir frames "front" "2026-05-15"))))
    (testing "frame-path"
      (is (= "/tmp/frames/front/2026-05-15/frame-123456.jpg"
             (tl/frame-path frames "front" "2026-05-15" 123456))))
    (testing "daily-video-path"
      (is (= "/tmp/videos/daily/front/2026-05-15.mp4"
             (tl/daily-video-path videos "front" "2026-05-15"))))
    (testing "monthly-video-path"
      (is (= "/tmp/videos/monthly/front/2026-05.mp4"
             (tl/monthly-video-path videos "front" "2026-05"))))
    (testing "yearly-video-path"
      (is (= "/tmp/videos/yearly/front/2026.mp4"
             (tl/yearly-video-path videos "front" "2026"))))))
