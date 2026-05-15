(ns pasquet.backend.timelapse
  (:require [chime.core :as chime]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.biffweb :as biff])
  (:import [java.time LocalDate ZoneId]
           [java.time.format DateTimeFormatter]))

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

(defn ffmpeg-daily-cmd [fps frame-dir output-path]
  ["ffmpeg" "-framerate" (str fps)
   "-pattern_type" "glob" "-i" (str frame-dir "/*.jpg")
   "-c:v" "libx264" "-preset" "slow" "-crf" "28"
   "-pix_fmt" "yuv420p" "-movflags" "+faststart"
   "-y" output-path])

(defn ffmpeg-concat-cmd [concat-file output-path]
  ["ffmpeg" "-f" "concat" "-safe" "0"
   "-i" concat-file
   "-c" "copy" "-movflags" "+faststart"
   "-y" output-path])

(defn fetch-snapshot! [host api-key camera-id]
  (:body (http/get (str host "/proxy/protect/integration/v1/cameras/" camera-id "/snapshot")
                   {:headers {"X-API-Key" api-key}
                    :as :byte-array
                    :insecure? true})))

(defn capture-frames! [{:keys [unifi/host unifi/api-key unifi/cameras
                                timelapse/frames-path]}]
  (let [cameras (parse-cameras cameras)
        today (str (LocalDate/now (ZoneId/of "UTC")))
        timestamp (System/currentTimeMillis)]
    (doseq [{:keys [name id]} cameras]
      (try
        (let [path (frame-path frames-path name today timestamp)]
          (io/make-parents path)
          (let [bytes (fetch-snapshot! host api-key id)]
            (with-open [out (io/output-stream (io/file path))]
              (.write out ^bytes bytes)))
          (log/info "Captured frame for" name "at" path))
        (catch Exception e
          (log/warn "Failed to capture frame for" name ":" (.getMessage e)))))))

(defn- delete-dir! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (.delete f)))

(defn compile-daily! [{:keys [timelapse/fps timelapse/frames-path timelapse/videos-path
                               unifi/cameras]}]
  (let [cameras (parse-cameras cameras)
        yesterday (str (.minusDays (LocalDate/now (ZoneId/of "UTC")) 1))]
    (doseq [{:keys [name]} cameras]
      (let [fdir (frame-dir frames-path name yesterday)
            output (daily-video-path videos-path name yesterday)]
        (when (.isDirectory (io/file fdir))
          (try
            (io/make-parents output)
            (let [result (apply shell/sh (ffmpeg-daily-cmd fps fdir output))]
              (if (zero? (:exit result))
                (do
                  (log/info "Compiled daily video for" name yesterday)
                  (delete-dir! fdir))
                (log/error "ffmpeg daily failed for" name yesterday (:err result))))
            (catch Exception e
              (log/error "Daily compilation failed for" name yesterday (.getMessage e)))))))))

(defn- write-concat-file! [concat-path video-files]
  (io/make-parents concat-path)
  (spit concat-path
        (str/join "\n" (map #(str "file '" (.getAbsolutePath %) "'") video-files))))

(defn- concat-videos! [video-files concat-path output-path label]
  (try
    (io/make-parents output-path)
    (write-concat-file! concat-path video-files)
    (let [result (apply shell/sh (ffmpeg-concat-cmd concat-path output-path))]
      (io/delete-file concat-path true)
      (if (zero? (:exit result))
        (do
          (log/info "Compiled" label "video:" output-path)
          (doseq [f video-files]
            (io/delete-file f true))
          true)
        (do
          (log/error "ffmpeg concat failed for" label (:err result))
          false)))
    (catch Exception e
      (io/delete-file concat-path true)
      (log/error label "compilation failed:" (.getMessage e))
      false)))

(defn- find-uncompiled-months
  "Returns year-month strings (e.g. \"2026-05\") for which daily videos exist but no monthly video does."
  [videos-path camera-name]
  (let [daily-dir (io/file videos-path "daily" camera-name)
        monthly-dir (io/file videos-path "monthly" camera-name)]
    (when (and daily-dir (.isDirectory daily-dir))
      (let [daily-months (->> (.listFiles daily-dir)
                              (filter #(str/ends-with? (.getName %) ".mp4"))
                              (map #(subs (.getName %) 0 7))
                              set)
            existing-months (->> (when (.isDirectory monthly-dir)
                                  (.listFiles monthly-dir))
                                (filter some?)
                                (filter #(str/ends-with? (.getName %) ".mp4"))
                                (map #(subs (.getName %) 0 7))
                                set)
            today-month (.format (LocalDate/now (ZoneId/of "UTC"))
                                 (DateTimeFormatter/ofPattern "yyyy-MM"))]
        ;; Don't compile the current month (still accumulating)
        (sort (disj (set/difference daily-months existing-months) today-month))))))

(defn- find-uncompiled-years
  "Returns year strings (e.g. \"2026\") for which monthly videos exist but no yearly video does."
  [videos-path camera-name]
  (let [monthly-dir (io/file videos-path "monthly" camera-name)
        yearly-dir (io/file videos-path "yearly" camera-name)]
    (when (and monthly-dir (.isDirectory monthly-dir))
      (let [monthly-years (->> (.listFiles monthly-dir)
                               (filter #(str/ends-with? (.getName %) ".mp4"))
                               (map #(subs (.getName %) 0 4))
                               set)
            existing-years (->> (when (.isDirectory yearly-dir)
                                  (.listFiles yearly-dir))
                                (filter some?)
                                (filter #(str/ends-with? (.getName %) ".mp4"))
                                (map #(subs (.getName %) 0 4))
                                set)
            current-year (str (.getYear (LocalDate/now (ZoneId/of "UTC"))))]
        ;; Don't compile the current year (still accumulating)
        (sort (disj (set/difference monthly-years existing-years) current-year))))))

(defn compile-rollup! [{:keys [timelapse/videos-path unifi/cameras]}]
  (let [cameras (parse-cameras cameras)]
    ;; Monthly rollups — find all months with daily videos but no monthly video yet
    (doseq [{:keys [name]} cameras]
      (doseq [year-month (find-uncompiled-months videos-path name)]
        (let [daily-dir (io/file videos-path "daily" name)
              monthly-out (monthly-video-path videos-path name year-month)
              concat-path (str videos-path "/monthly/" name "/concat-" year-month ".txt")
              daily-files (->> (.listFiles daily-dir)
                               (filter #(str/starts-with? (.getName %) year-month))
                               (filter #(str/ends-with? (.getName %) ".mp4"))
                               (sort-by #(.getName %))
                               vec)]
          (when (seq daily-files)
            (concat-videos! daily-files concat-path monthly-out
                            (str "monthly " name " " year-month))))))
    ;; Yearly rollups — find all years with monthly videos but no yearly video yet
    (doseq [{:keys [name]} cameras]
      (doseq [year (find-uncompiled-years videos-path name)]
        (let [monthly-dir (io/file videos-path "monthly" name)
              yearly-out (yearly-video-path videos-path name year)
              concat-path (str videos-path "/yearly/" name "/concat-" year ".txt")
              monthly-files (->> (.listFiles monthly-dir)
                                 (filter #(str/starts-with? (.getName %) year))
                                 (filter #(str/ends-with? (.getName %) ".mp4"))
                                 (sort-by #(.getName %))
                                 vec)]
          (when (seq monthly-files)
            (concat-videos! monthly-files concat-path yearly-out
                            (str "yearly " name " " year))))))))

(defn- every-n-seconds [n]
  (iterate #(biff/add-seconds % n) (java.util.Date.)))

(defn- daily-at-midnight []
  (let [tomorrow-midnight (-> (LocalDate/now (ZoneId/of "UTC"))
                              (.plusDays 1)
                              (.atStartOfDay (ZoneId/of "UTC"))
                              .toInstant
                              java.util.Date/from)]
    (iterate #(biff/add-seconds % 86400) tomorrow-midnight)))

(defn use-timelapse [{:keys [timelapse/fps timelapse/day-duration
                              unifi/cameras] :as ctx}]
  (if (and fps day-duration cameras)
    (let [interval (capture-interval-seconds fps day-duration)
          _ (log/info "Starting timelapse: capture every" interval "seconds for"
                      (count (parse-cameras cameras)) "cameras")
          capture-sched (chime/chime-at
                          (every-n-seconds interval)
                          (fn [_] (capture-frames! ctx)))
          daily-sched (chime/chime-at
                        (daily-at-midnight)
                        (fn [_]
                          (compile-daily! ctx)
                          (compile-rollup! ctx)))]
      (update ctx :biff/stop conj
              #(.close capture-sched)
              #(.close daily-sched)))
    (do
      (log/warn "Timelapse not configured (missing fps, day-duration, or cameras). Skipping.")
      ctx)))
