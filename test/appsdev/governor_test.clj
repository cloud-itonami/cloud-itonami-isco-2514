(ns appsdev.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [appsdev.store :as store]
            [appsdev.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Dev"})
    (store/register-ticket! st {:ticket-id "T-1" :client-id "client-1"
                                :title "add invoice export"})
    (store/register-test-run! st {:run-id "run-green" :client-id "client-1"
                                  :commit "abc123" :status :green})
    (store/register-test-run! st {:run-id "run-red" :client-id "client-1"
                                  :commit "abc123" :status :red})
    (store/register-test-run! st {:run-id "run-stale" :client-id "client-1"
                                  :commit "old999" :status :green})
    st))

(defn- submit [run-id commit]
  {:op :submit-change :effect :propose :ticket-id "T-1"
   :run-id run-id :commit commit :confidence 0.9 :stake :medium})

(def ^:private req {:client-id "client-1"})

(deftest ok-on-green-run-for-same-commit
  (let [st (fresh-store)
        v (governor/check req {} (submit "run-green" "abc123") st)]
    (is (:ok? v))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (submit "run-green" "abc123") st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (submit "run-green" "abc123")
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-invented-requirement
  (let [st (fresh-store)
        v (governor/check req {} (assoc (submit "run-green" "abc123")
                                        :ticket-id "T-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-ticket (:rule %)) (:violations v)))))

(deftest hard-on-missing-ticket
  (let [st (fresh-store)
        v (governor/check req {} (assoc (submit "run-green" "abc123")
                                        :ticket-id nil) st)]
    (is (:hard? v))
    (is (some #(= :no-ticket (:rule %)) (:violations v)))))

(deftest hard-on-submission-without-evidence
  (let [st (fresh-store)
        v (governor/check req {} (submit nil "abc123") st)]
    (is (:hard? v))
    (is (some #(= :no-test-run (:rule %)) (:violations v)))))

(deftest hard-on-red-run
  (testing "a red run is not approvable — fix it"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (submit "run-red" "abc123")
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :test-not-green (:rule %)) (:violations v))))))

(deftest hard-on-stale-green-evidence
  (testing "a green run for ANOTHER commit proves nothing about this one —
            evidence freshness is identity, not recency"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (submit "run-stale" "abc123")
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :test-commit-mismatch (:rule %)) (:violations v))))))

(deftest draft-needs-ticket-but-no-evidence
  (let [st (fresh-store)
        v (governor/check req {} {:op :draft-change :effect :propose
                                  :ticket-id "T-1" :confidence 0.9 :stake :low} st)]
    (is (:ok? v))))

(deftest escalates-deployment
  (let [st (fresh-store)
        v (governor/check req {} {:op :deploy-release :effect :propose
                                  :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} {:op :draft-change :effect :propose
                                  :ticket-id "T-1" :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
