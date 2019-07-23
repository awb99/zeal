(ns zeal.serve
  (:require [aleph.http :as http]
            [mount.core :as mount :refer [defstate]]
            [manifold.stream :as s]
            [cognitect.transit :as transit]
            [uix.dom.alpha :as uix.dom]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [zeal.ui.views :as views]
            [clojure.core.async :as a]
            [zeal.core :as zc]
            [zeal.db :as db]
            [manifold.deferred :as d]
            [byte-streams :as bs])
  (:import (java.io ByteArrayOutputStream InputStream)))

(defn transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  (let [output (ByteArrayOutputStream.)]
    (transit/write (transit/writer output type opts) out)
    (.toByteArray output)))

(defn parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [^InputStream in type & [opts]]
  (transit/read (transit/reader in type opts)))

(defn transit-encode-json-with-meta [out & [opts]]
  (transit-encode out :json (merge {:transform transit/write-meta} opts)))

(defn handler [req]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    "hello!"})

(defn html []
  [:<>
   [:meta {:charset "UTF-8"}]
   [views/document
    {:meta [{:name "viewport"
             :content "width=device-width, initial-scale=1"}]
     :styles ["
    .CodeMirror { height: auto !important; }
    .prewrap { white-space: pre-wrap; }
    .break-all { word-break: break-all };"]
     :links  ["css/tachyons.css"
              "css/font-awesome/css/all.css"
              "css/codemirror.css"
              "css/codemirror-show-hint.css"
              "https://fonts.googleapis.com/css?family=Faster+One&display=swap"]
     :js     [{:src "js/compiled/main.js"}
              {:script "zeal.ui.core.init()"}]}]])

(defn index [_]
  (let [res (s/stream)]
    (future
     (uix.dom/render-to-stream
      [html] {:on-chunk #(s/put! res %)})
     (s/close! res))
    {:status  200
     :headers {"content-type" "text/html"}
     :body    res}))


(def non-websocket-request
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})

(defn ws-search-eval-log-handler
  [req]
  (-> (http/websocket-connection req)
      (d/chain
       (fn [socket]
         (s/consume
          (fn [msg]
            (let [search-res (zc/search-eval-log msg)]
              (s/put! socket
                      (transit-encode search-res :json))))
          socket)
         socket))
      (d/catch
       (fn [_]
         non-websocket-request))))

(defn echo-handler
  [req]
  (-> (http/websocket-connection req)
      (d/chain
       (fn [socket]
         (s/connect socket socket)))
      (d/catch
       (fn [_]
         non-websocket-request))))

(defn dev-handler [req]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (uix.dom/render-to-string [html])})

(def multi-handler-req-dispatch-fn first)

(defmulti multi-handler-response-fn
  (fn [body handled] (multi-handler-req-dispatch-fn body)))

(defmethod multi-handler-response-fn :eval-and-log
  [_ handled]
  {:status  200
   :headers {"content-type" "application/transit+json"}
   :body    (try
              (transit-encode-json-with-meta handled)
              (catch Exception e
                ;; transit can't handle classes and vars so we fallback to string
                (println ::str-fallback e)
                (transit-encode-json-with-meta (update handled :result pr-str))))})

(defmethod multi-handler-response-fn :default
  [_ handled]
  {:status  200
   :headers {"content-type" "application/transit+json"}
   :body    (transit-encode handled
                            :json
                            {:transform transit/write-meta})})

(defn- wrap-multi-handler
  ([handler] (fn [req] (wrap-multi-handler handler req)))
  ([handler req]
   (let [body    (parse-transit (:body req) :json)
         handled (handler body)]
     (multi-handler-response-fn body handled))))

(defmulti multi-handler first)

(defmethod multi-handler :eval-and-log
  [[_ exec-ent]]
  (zc/eval-and-log-exec-ent! exec-ent))

(defmethod multi-handler :search
  [[_ {:keys [q]}]]
  (zc/search-eval-log q))

(defmethod multi-handler :recent-exec-ents
  [[_ opts]]
  (zc/recent-exec-ents opts))

(defmethod multi-handler :history
  [[_ {id :crux.db/id}]]
  (db/entity-history id {:with-history-info? true}))

(defmethod multi-handler :merge-entity
  [[_ {id :crux.db/id :as ent}]]
  (some-> id
          db/entity
          (merge ent)
          vector
          (db/put! {:blocking? true}))
  (db/entity id))

(def resource-handler
  (-> (constantly {:status 200})
      (wrap-resource "public")
      (wrap-content-type)
      (wrap-not-modified)))

(defn handler [{:as req :keys [uri]}]
  (let [handle
        (case uri
          "/" index
          "/echo" echo-handler
          "/dispatch" (wrap-multi-handler multi-handler)
          ;; todo add 404
          resource-handler)]
    (handle req)))

(defstate server
  :start (http/start-server handler {:port 3400})
  :stop (.close server))

(comment
 (mount/stop)
 (mount/start)
 )

