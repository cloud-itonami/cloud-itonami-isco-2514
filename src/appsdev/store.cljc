(ns appsdev.store
  "SSoT for the ISCO-08 2514 community applications-programming actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client   — a registered organization (:client-id, :name)
    ticket   — a registered requirement {:ticket-id :client-id :title}.
               Every change must trace to one (no invented
               requirements).
    test-run — a registered test evidence record {:run-id :client-id
               :commit :status} where :status is :green or :red.
               Evidence is commit-scoped: a green run for another
               commit proves nothing about this one.
    record   — a committed operating record (change draft, submitted
               change, deployed release) — written ONLY via
               commit-record!.
    ledger   — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (ticket [s ticket-id])
  (test-run [s run-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-ticket! [s t])
  (register-test-run! [s r])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (ticket [_ ticket-id] (get-in @a [:tickets ticket-id]))
  (test-run [_ run-id] (get-in @a [:test-runs run-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-ticket! [s t]
    (swap! a assoc-in [:tickets (:ticket-id t)] t) s)
  (register-test-run! [s r]
    (swap! a assoc-in [:test-runs (:run-id r)] r) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :tickets {} :test-runs {}
                                    :records [] :ledger []}
                                   seed)))))
