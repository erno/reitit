(ns reitit.ring-coercion-test
  (:require [clojure.test :refer [deftest testing is]]
            [schema.core :as s]
            [spec-tools.data-spec :as ds]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.coercion.spec :as spec]
            [reitit.coercion.malli :as malli]
            [reitit.coercion.schema :as schema]
            #?@(:clj [[muuntaja.middleware]
                      [jsonista.core :as j]]))
  #?(:clj
     (:import (clojure.lang ExceptionInfo)
              (java.io ByteArrayInputStream))))

(defn handler [{{{:keys [a]} :query
                 {:keys [b]} :body
                 {:keys [c]} :form
                 {:keys [d]} :header
                 {:keys [e]} :path :as parameters} :parameters}]
  ;; extra keys are stripped off
  (assert (every? #{0 1} (map (comp count val) parameters)))

  (if (= 666 a)
    {:status 500
     :body {:evil true}}
    {:status 200
     :body {:total (+ (or a 101) b c d e)}}))

(def valid-request1
  {:uri "/api/plus/5"
   :request-method :get
   :muuntaja/request {:format "application/json"}
   :query-params {"a" "1"}
   :body-params {:b 2}
   :form-params {:c 3}
   :headers {"d" "4"}})

(def valid-request2
  {:uri "/api/plus/5"
   :request-method :get
   :muuntaja/request {:format "application/json"}
   :query-params {}
   :body-params {:b 2}
   :form-params {:c 3}
   :headers {"d" "4"}})

(def valid-request3
  {:uri "/api/plus/5"
   :request-method :get
   :muuntaja/request {:format "application/edn"}
   :query-params {"a" "1", "EXTRA" "VALUE"}
   :body-params {:b 2, :EXTRA "VALUE"}
   :form-params {:c 3, :EXTRA "VALUE"}
   :headers {"d" "4", "EXTRA" "VALUE"}})

(def invalid-request1
  {:uri "/api/plus/5"
   :request-method :get})

(def invalid-request2
  {:uri "/api/plus/5"
   :request-method :get
   :query-params {"a" "1"}
   :body-params {:b 2}
   :form-params {:c 3}
   :headers {"d" "-40"}})

(deftest spec-coercion-test
  (let [create (fn [middleware]
                 (ring/ring-handler
                   (ring/router
                     ["/api"
                      ["/plus/:e"
                       {:get {:parameters {:query {(ds/opt :a) int?}
                                           :body {:b int?}
                                           :form {:c int?}
                                           :header {:d int?}
                                           :path {:e int?}}
                              :responses {200 {:body {:total pos-int?}}
                                          500 {:description "fail"}}
                              :handler handler}}]]
                     {:data {:middleware middleware
                             :coercion spec/coercion}})))]

    (testing "without exception handling"
      (let [app (create [rrc/coerce-request-middleware
                         rrc/coerce-response-middleware])]

        (testing "all good"
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request1)))
          (is (= {:status 200
                  :body {:total 115}}
                 (app valid-request2)))
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request3)))
          (is (= {:status 500
                  :body {:evil true}}
                 (app (assoc-in valid-request1 [:query-params "a"] "666")))))

        (testing "invalid request"
          (is (thrown-with-msg?
                ExceptionInfo
                #"Request coercion failed"
                (app invalid-request1))))

        (testing "invalid response"
          (is (thrown-with-msg?
                ExceptionInfo
                #"Response coercion failed"
                (app invalid-request2))))))

    (testing "with exception handling"
      (let [app (create [rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware])]

        (testing "all good"
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request1))))

        (testing "invalid request"
          (let [{:keys [status body]} (app invalid-request1)
                problems (:problems body)]
            (is (= 1 (count problems)))
            (is (= 400 status))))

        (testing "invalid response"
          (let [{:keys [status]} (app invalid-request2)]
            (is (= 500 status))))))))

(deftest schema-coercion-test
  (let [create (fn [middleware]
                 (ring/ring-handler
                   (ring/router
                     ["/api"
                      ["/plus/:e"
                       {:get {:parameters {:query {(s/optional-key :a) s/Int}
                                           :body {:b s/Int}
                                           :form {:c s/Int}
                                           :header {:d s/Int}
                                           :path {:e s/Int}}
                              :responses {200 {:body {:total (s/constrained s/Int pos? 'positive)}}
                                          500 {:description "fail"}}
                              :handler handler}}]]
                     {:data {:middleware middleware
                             :coercion schema/coercion}})))]

    (testing "withut exception handling"
      (let [app (create [rrc/coerce-request-middleware
                         rrc/coerce-response-middleware])]

        (testing "all good"
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request1)))
          (is (= {:status 200
                  :body {:total 115}}
                 (app valid-request2)))
          (is (= {:status 500
                  :body {:evil true}}
                 (app (assoc-in valid-request1 [:query-params "a"] "666")))))

        (testing "invalid request"
          (is (thrown-with-msg?
                ExceptionInfo
                #"Request coercion failed"
                (app invalid-request1)))
          (is (thrown-with-msg?
                ExceptionInfo
                #"Request coercion failed"
                (app valid-request3))))

        (testing "invalid response"
          (is (thrown-with-msg?
                ExceptionInfo
                #"Response coercion failed"
                (app invalid-request2))))))

    (testing "with exception handling"
      (let [app (create [rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware])]

        (testing "all good"
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request1))))

        (testing "invalid request"
          (let [{:keys [status]} (app invalid-request1)]
            (is (= 400 status))))

        (testing "invalid response"
          (let [{:keys [status]} (app invalid-request2)]
            (is (= 500 status))))))))

(deftest malli-coercion-test
  (let [create (fn [middleware]
                 (ring/ring-handler
                   (ring/router
                     ["/api"
                      ["/plus/:e" {:get {:parameters {:query [:map [:a {:optional true} int?]]
                                                      :body [:map [:b int?]]
                                                      :form [:map [:c [int? {:default 3}]]]
                                                      :header [:map [:d int?]]
                                                      :path [:map [:e int?]]}
                                         :responses {200 {:body [:map [:total pos-int?]]}
                                                     500 {:description "fail"}}
                                         :handler handler}}]]
                     {:data {:middleware middleware
                             :coercion malli/coercion}})))]

    (testing "withut exception handling"
      (let [app (create [rrc/coerce-request-middleware
                         rrc/coerce-response-middleware])]

        (testing "all good"
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request1)))
          (is (= {:status 200
                  :body {:total 115}}
                 (app valid-request2)))
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request3)))
          (testing "default values work"
            (is (= {:status 200
                    :body {:total 15}}
                   (app (update valid-request3 :form-params dissoc :c)))))
          (is (= {:status 500
                  :body {:evil true}}
                 (app (assoc-in valid-request1 [:query-params "a"] "666")))))

        (testing "invalid request"
          (is (thrown-with-msg?
                ExceptionInfo
                #"Request coercion failed"
                (app invalid-request1))))

        (testing "invalid response"
          (is (thrown-with-msg?
                ExceptionInfo
                #"Response coercion failed"
                (app invalid-request2))))))

    (testing "with exception handling"
      (let [app (create [rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware])]

        (testing "all good"
          (is (= {:status 200
                  :body {:total 15}}
                 (app valid-request1))))

        (testing "invalid request"
          (let [{:keys [status]} (app invalid-request1)]
            (is (= 400 status))))

        (testing "invalid response"
          (let [{:keys [status]} (app invalid-request2)]
            (is (= 500 status))))))

    (testing "open & closed schemas"
      (let [endpoint (fn [schema]
                       {:get {:parameters {:body schema}
                              :responses {200 {:body schema}}
                              :handler (fn [{{:keys [body]} :parameters}]
                                         {:status 200, :body (assoc body :response true)})}})
            ->app (fn [options]
                    (ring/ring-handler
                      (ring/router
                        ["/api"
                         ["/default" (endpoint [:map [:x int?]])]
                         ["/closed" (endpoint [:map {:closed true} [:x int?]])]
                         ["/open" (endpoint [:map {:closed false} [:x int?]])]]
                        {:data {:middleware [rrc/coerce-exceptions-middleware
                                             rrc/coerce-request-middleware
                                             rrc/coerce-response-middleware]
                                :coercion (malli/create options)}})))
            ->request (fn [uri] {:uri (str "/api/" uri)
                                 :request-method :get
                                 :muuntaja/request {:format "application/json"}
                                 :body-params {:x 1, :request true}})]

        (testing "with defaults"
          (let [app (->app nil)]

            (testing "default: keys are stripped"
              (is (= {:status 200, :body {:x 1}}
                     (app (->request "default")))))

            (testing "closed: keys are stripped"
              (is (= {:status 200, :body {:x 1}}
                     (app (->request "closed")))))

            (testing "open: keys are NOT stripped"
              (is (= {:status 200, :body {:x 1, :request true, :response true}}
                     (app (->request "open")))))))

        (testing "when schemas are not closed"
          (let [app (->app {:compile identity})]

            (testing "default: keys are stripped"
              (is (= {:status 200, :body {:x 1}}
                     (app (->request "default")))))

            (testing "closed: keys are stripped"
              (is (= {:status 200, :body {:x 1}}
                     (app (->request "closed")))))

            (testing "open: keys are NOT stripped"
              (is (= {:status 200, :body {:x 1, :request true, :response true}}
                     (app (->request "open")))))))

        (testing "when schemas are not closed and extra keys are not stripped"
          (let [app (->app {:compile identity, :strip-extra-keys false})]
            (testing "default: keys are NOT stripped"
              (is (= {:status 200, :body {:x 1, :request true, :response true}}
                     (app (->request "default")))))

            (testing "closed: FAILS for extra keys"
              (is (= 400 (:status (app (->request "closed"))))))

            (testing "open: keys are NOT stripped"
              (is (= {:status 200, :body {:x 1, :request true, :response true}}
                     (app (->request "open")))))))))))

#?(:clj
   (deftest muuntaja-test
     (let [app (ring/ring-handler
                 (ring/router
                   ["/api"
                    ["/plus"
                     {:post {:parameters {:body {:int int?, :keyword keyword?}}
                             :responses {200 {:body {:int int?, :keyword keyword?}}}
                             :handler (fn [{{:keys [body]} :parameters}]
                                        {:status 200
                                         :body body})}}]]
                   {:data {:middleware [muuntaja.middleware/wrap-format
                                        rrc/coerce-request-middleware
                                        rrc/coerce-response-middleware]
                           :coercion spec/coercion}}))
           request (fn [content-type body]
                     (-> {:request-method :post
                          :headers {"content-type" content-type, "accept" content-type}
                          :uri "/api/plus"
                          :body body}))
           data-edn {:int 1 :keyword :kikka}
           data-json {:int 1 :keyword "kikka"}]

       (testing "json coercion"
         (let [e2e #(-> (request "application/json" (ByteArrayInputStream. (j/write-value-as-bytes %)))
                        (app) :body (slurp) (j/read-value (j/object-mapper {:decode-key-fn true})))]
           (is (= data-json (e2e (assoc data-edn :EXTRA "VALUE"))))
           (is (= data-json (e2e (assoc data-json :EXTRA "VALUE"))))))

       (testing "edn coercion"
         (let [e2e #(-> (request "application/edn" (pr-str %))
                        (app) :body slurp (read-string))]
           (is (= data-edn (e2e (assoc data-edn :EXTRA "VALUE"))))
           (is (thrown? ExceptionInfo (e2e data-json))))))))
