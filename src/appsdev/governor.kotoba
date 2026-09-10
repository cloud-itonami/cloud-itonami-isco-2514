(ns appsdev.governor
  "ApplicationsProgrammingGovernor — the independent safety/traceability
  layer for the ISCO-08 2514 community applications-programming actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.governor. The
  programming-specific twist: TEST EVIDENCE IS COMMIT-SCOPED — a change
  submission must cite a registered test run that is BOTH green AND for
  the exact commit being submitted. 'The tests were green yesterday'
  (on another commit) proves nothing; evidence freshness is identity,
  not recency.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. requirement basis — every change must cite a REGISTERED ticket
                           belonging to this client (no invented
                           requirements).
    4. test evidence     — a :submit-change must cite a REGISTERED test
                           run of this client that is :green AND whose
                           :commit equals the submission's :commit.
                           A red run, a missing run, or a green run for
                           a different commit are all held at any
                           confidence — rerun the tests on THIS commit.
  ESCALATION invariants (:escalate? true, human sign-off):
    5. :op :deploy-release (real deployment — always human).
    6. low confidence (< `confidence-floor`)."
  (:require [appsdev.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record tik run]
  (let [{:keys [op ticket-id run-id commit]} proposal
        change-op? (contains? #{:draft-change :submit-change} op)
        submit? (= :submit-change op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and change-op? (nil? ticket-id))
      (conj {:rule :no-ticket :detail "変更は要件チケットの引用が必須（要件の捏造禁止）"})

      (and change-op? ticket-id (nil? tik))
      (conj {:rule :unknown-ticket :detail (str "未登録 ticket: " ticket-id)})

      (and change-op? tik (not= (:client-id tik) (:client-id request)))
      (conj {:rule :ticket-wrong-client :detail "ticket が別 client のもの"})

      (and submit? (nil? run-id))
      (conj {:rule :no-test-run
             :detail "変更提出はテスト実行記録の引用が必須（証拠なき提出は承認不可）"})

      (and submit? run-id (nil? run))
      (conj {:rule :unknown-test-run :detail (str "未登録 test run: " run-id)})

      (and submit? run (not= (:client-id run) (:client-id request)))
      (conj {:rule :test-run-wrong-client :detail "test run が別 client のもの"})

      (and submit? run (= (:client-id run) (:client-id request))
           (not= :green (:status run)))
      (conj {:rule :test-not-green
             :detail (str "test run の status が " (:status run)
                          "（green でない変更の提出は承認不可 — 直すこと）")})

      (and submit? run (= :green (:status run))
           (not= (:commit run) commit))
      (conj {:rule :test-commit-mismatch
             :detail (str "test run の commit " (:commit run)
                          " ≠ 提出 commit " commit
                          "（別 commit の green は証拠にならない — この commit で回し直すこと）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `appsdev.store/Store`. Pure — never mutates the
  store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        tik (some->> (:ticket-id proposal) (store/ticket store))
        run (some->> (:run-id proposal) (store/test-run store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record tik run)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :deploy-release (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
