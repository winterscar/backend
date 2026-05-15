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

(deftest ffmpeg-daily-cmd-test
  (is (= ["ffmpeg" "-framerate" "30"
           "-pattern_type" "glob" "-i" "/tmp/frames/cam/2026-05-15/*.jpg"
           "-c:v" "libx264" "-preset" "slow" "-crf" "28"
           "-pix_fmt" "yuv420p" "-movflags" "+faststart"
           "-y" "/tmp/videos/daily/cam/2026-05-15.mp4"]
         (tl/ffmpeg-daily-cmd 30 "/tmp/frames/cam/2026-05-15" "/tmp/videos/daily/cam/2026-05-15.mp4"))))

(deftest ffmpeg-concat-cmd-test
  (is (= ["ffmpeg" "-f" "concat" "-safe" "0"
           "-i" "/tmp/concat.txt"
           "-c" "copy" "-movflags" "+faststart"
           "-y" "/tmp/output.mp4"]
         (tl/ffmpeg-concat-cmd "/tmp/concat.txt" "/tmp/output.mp4"))))
