(ns ziggurat.timestamp-transformer
  (:require [ziggurat.kafka-delay :refer [calculate-and-report-kafka-delay]]
            [clojure.tools.logging :as log]
            [ziggurat.sentry :refer [sentry-reporter]]
            [sentry-clj.async :as sentry]
            [ziggurat.util.time :refer [get-current-time-in-millis get-timestamp-from-record]]
            [protobuf.impl.flatland.mapdef :as protodef])
  (:import [org.apache.kafka.streams KeyValue]
           [org.apache.kafka.streams.kstream Transformer]
           [org.apache.kafka.streams.processor TimestampExtractor ProcessorContext]))

(defn- message-to-process? [message-timestamp oldest-processed-message-in-s]
  (let [current-time (get-current-time-in-millis)
        allowed-time (- current-time (* 1000 oldest-processed-message-in-s))]
    (> message-timestamp allowed-time)))

(deftype IngestionTimeExtractor [] TimestampExtractor
         (extract [_ record _]
           (let [ingestion-time (get-timestamp-from-record record)]
             (if (neg? ingestion-time)
               (get-current-time-in-millis)
               ingestion-time))))

(defn deserialize-message-2
  [message proto-class]
  (try
    (let [proto-klass  (protodef/mapdef proto-class)
          loaded-proto (protodef/parse proto-klass message)
          proto-keys   (-> proto-klass
                           protodef/mapdef->schema
                           :fields
                           keys)]
      (select-keys loaded-proto proto-keys))
    (catch Throwable e
      (log/error e (str "Couldn't parse the message with proto - " proto-class)))))

(defn- get-key-proto-class
  [topic]
  (if (= topic "driver-reward-points")
    com.gojek.esb.driverrewards.DriverRewardPointLogKey
    com.gojek.esb.driverstatistics.DriverStatsUpdatedLogKey))

(defn- get-value-proto-class
  [topic]
  (if (= topic "driver-reward-points")
    com.gojek.esb.driverrewards.DriverRewardPointsLogMessage
    com.gojek.esb.driverstatistics.DriverStatsUpdatedLogMessage))

(deftype TimestampTransformer [^{:volatile-mutable true} processor-context metric-namespace oldest-processed-message-in-s additional-tags] Transformer
         (^void init [_ ^ProcessorContext context]
           (set! processor-context context))
         (transform [_ record-key record-value]
           (let [message-time (.timestamp processor-context)
                 partition    (.partition processor-context)
                 topic        (.topic processor-context)
                 metadata     (str "stream record metadata--> " "record-key: " (deserialize-message-2 record-key (get-key-proto-class topic))
                                   " record-value: " (deserialize-message-2 record-value (get-value-proto-class topic))
                                   " partition: " partition
                                   " topic: " topic)]
             (log/info metadata)
             ;; HACK:
             ;(sentry/report-error sentry-reporter (Exception. "this exception is made for logging the stream metadata") metadata)
             (when (message-to-process? message-time oldest-processed-message-in-s)
               (calculate-and-report-kafka-delay metric-namespace message-time additional-tags)
               (KeyValue/pair record-key record-value))))
         (close [_] nil))

(defn create
  ([metric-namespace process-message-since-in-s]
   (create metric-namespace process-message-since-in-s nil))
  ([metric-namespace process-message-since-in-s additional-tags]
   (TimestampTransformer. nil metric-namespace process-message-since-in-s additional-tags)))
