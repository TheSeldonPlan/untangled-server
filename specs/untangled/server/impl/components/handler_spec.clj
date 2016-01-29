(ns ^:focused untangled.server.impl.components.handler-spec
  (:require [untangled-spec.core :refer [specification assertions provided component behavior]]
            [clojure.test :refer [is]]
            [untangled.server.impl.components.handler :as h]
            [om.next.server :as om])
  (:import (clojure.lang ExceptionInfo)))

(specification "generate-response"
  (assertions
    "returns a map with status, header, and body."
    (keys (h/generate-response {})) => [:status :headers :body]

    "merges Content-Type of transit json to the passed-in headers."
    (:headers (h/generate-response {:headers {:my :header}})) => {:my            :header
                                                                  "Content-Type" "application/transit+json"})
  (behavior "does not permit"
    (assertions
      "a \"Content-Type\" key in the header."
      (h/generate-response {:headers {"Content-Type" "not-allowed"}}) =throws=> (AssertionError #"headers")

      "a status code less than 100."
      (h/generate-response {:status 99}) =throws=> (AssertionError #"100")

      "a status code greater than or equal to 600."
      (h/generate-response {:status 600}) =throws=> (AssertionError #"600"))))

(specification "An API Response"
  (let [my-read (fn [_ key _] {:value (case key
                                        :foo "success"
                                        :bar (throw (ex-info "Oops" {:my :bad}))
                                        :bar' (throw (ex-info "Oops'" {:status 402 :body "quite an error"}))
                                        :baz (throw (IllegalArgumentException.)))})

        my-mutate (fn [_ key _] {:action (condp = key
                                           'foo (fn [] "success")
                                           'bar (fn [] (throw (ex-info "Oops" {:my :bad})))
                                           'bar' (fn [] (throw (ex-info "Oops'" {:status 402 :body "quite an error"})))
                                           'baz (fn [] (throw (IllegalArgumentException.))))})

        parser (om/parser {:read my-read :mutate my-mutate})
        parse-result (fn [query] (h/api {:parser parser :transit-params query}))]

    (behavior "for Om reads"
      (behavior "for a valid request"
        (behavior "returns a query response"
          (let [result (parse-result [:foo])]
            (assertions
              "with a body containing the expected parse result."
              (:body result) => {:foo "success"}))))

      (behavior "for an invalid request"
        (behavior "when the parser generates an expected error"
          (let [result (parse-result [:bar'])]
            (assertions
              "returns a status code."
              (:status result) =fn=> (complement nil?)

              "returns body if provided."
              (:body result) => "quite an error")))

        (behavior "when the parser generates an unexpected error"
          (let [result (parse-result [:bar])]
            (assertions
              "returns a 500 http status code."
              (:status result) => 500

              "contains an exception in the response body."
              (:body result) =fn=> (partial instance? ExceptionInfo))))

        (behavior "when the parser does not generate the error"
          (let [result (parse-result [:baz])]
            (assertions
              "returns a 500 http status code."
              (:status result) => 500

              "returns exception data in the response body."
              (:body result) =fn=> (partial instance? IllegalArgumentException))))))

    (behavior "for Om mutates"
      (behavior "for a valid request"
        (behavior "returns a query response"
          (let [result (parse-result ['(foo)])]
            (assertions
              "with a body containing the expected parse result."
              (:body result) => {'foo "success"}))))

      (behavior "for invalid requests (where one or more mutations fail)"
        (let [results [(parse-result ['(bar')])
                       (parse-result ['(bar)])
                       (parse-result ['(baz)])]]

          (behavior "returns a status code of 400."
            (doall (map #(is (= 400 (:status %))) results)))

          (behavior "returns failing mutation result in the body."
            (doall (map #(is (instance? Exception (-> % :body vals first :om.next/error))) results))))))))
