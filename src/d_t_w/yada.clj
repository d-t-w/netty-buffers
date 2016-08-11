(ns d-t-w.yada
  (:require [aleph.http :refer [start-server]]
            [yada.yada :refer [resource]]
            [yada.aleph :refer [listener]]
            [yada.request-body :as request-body])
  (:import [yada.multipart PartConsumer Partial]))

(defrecord MyPartial []
  Partial
  (continue [this _] this)
  (complete [_ state _] state))

(defrecord MyPartConsumer []
  PartConsumer
  (consume-part [_ _ _])
  (start-partial [_ _] (->MyPartial))
  (part-coercion-matcher [_] {}))

(defn start!
  []
  ;; Hook in our own part consumer
  (let [original-impl (get-method request-body/process-request-body "multipart/form-data")]
    (defmethod request-body/process-request-body "multipart/form-data"
      [ctx body-stream media-type & args]
      (let [new-ctx (assoc-in ctx [:options :part-consumer] (->MyPartConsumer))]
        (apply original-impl new-ctx body-stream media-type args))))

  (listener
    ["/upload"
     (resource
       {:methods
        {:post
         {:consumes "multipart/form-data"
          :response (constantly nil)}}})]
    {:port 3000}))
