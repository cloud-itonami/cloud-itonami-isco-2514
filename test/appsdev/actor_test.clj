(ns appsdev.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [appsdev.actor :as actor]
            [appsdev.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Dev"})
    (store/register-ticket! st {:ticket-id "T-1" :client-id "client-1"
                                :title "add invoice export"})
    (store/register-test-run! st {:run-id "run-green" :client-id "client-1"
                                  :commit "abc123" :status :green})
    st))

(deftest commits-an-evidenced-submission
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :submit-change :stake :medium
                 :ticket-id "T-1" :run-id "run-green" :commit "abc123"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-a-submission-with-stale-evidence
  (let [st (fresh-store)]
    (store/register-test-run! st {:run-id "run-stale" :client-id "client-1"
                                  :commit "old999" :status :green})
    (let [graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :submit-change :stake :medium
                   :ticket-id "T-1" :run-id "run-stale" :commit "abc123"}
          result (actor/run-request! graph request {} "thread-2")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest interrupts-then-deploys-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :deploy-release :stake :high}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
