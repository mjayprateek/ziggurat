(ns ziggurat.util.time
  (:import [java.time Instant]
           (org.apache.kafka.clients.consumer ConsumerRecord)))

(defn get-timestamp-from-record [^ConsumerRecord record]
  (.timestamp record))

(defn get-current-time-in-millis []
  (.toEpochMilli (Instant/now)))

