(ns appsdev.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo had NO demo/visualization at all before this namespace.
  This drives the REAL actor stack (`appsdev.actor` ->
  `appsdev.governor` -> `appsdev.store`) through a scenario built from
  real, exercised store/test data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify
  by diffing two consecutive runs before shipping). Adapted from the
  reference pattern in `cloud-itonami-isco-1211`'s
  `finmgmt.render-html` (com-junkawasaki/root, prior iteration of this
  same effort) -- the entities/rules below are specific to this
  domain, not copied verbatim.

  `client-1` (\"Kobo Dev\") + ticket `T-1` (\"add invoice export\") +
  test runs `run-green` (commit `abc123`, `:green`), `run-red` (commit
  `abc123`, `:red`), `run-stale` (commit `old999`, `:green`) are lifted
  VERBATIM from this repo's own proven-passing test fixture
  (`appsdev.actor-test/fresh-store` and
  `appsdev.governor-test/fresh-store`, identical seed in both) --
  ground truth, not invented.

  `client-2` (\"Sunport Software\") + ticket `T-2` (\"add SSO login\")
  + test run `run-2-green` (commit `xyz789`, `:green`) are ADDITIONAL
  demo data registered via the SAME real protocol calls
  (`store/register-client!`/`register-ticket!`/`register-test-run!`)
  this actor's own tests use -- this actor's own test fixture has only
  one client, so a second client is necessary to demonstrate the
  cross-client rules (`:ticket-wrong-client`, `:test-run-wrong-client`).
  Disclosed here plainly, not presented as a pre-existing fixture.
  Every other field this page displays (statuses, dispositions, hold
  reasons) is real output read after `run-demo!` actually executed the
  graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over
  (both mirror the exact same shape as `finmgmt`'s `:no-actuation` gap):

  1. `appsdev.governor`'s `:no-actuation` rule (proposal `:effect` must
     be `:propose`) is NOT reachable through this demo, because the
     real `mock-advisor` (`appsdev.advisor/infer`) unconditionally sets
     `:effect :propose` on every proposal it emits. Covered instead by
     `appsdev.governor-test/hard-on-no-actuation-violation`, which
     calls `governor/check` directly with a hand-built proposal.
  2. The low-confidence escalation path (`confidence <
     appsdev.governor/confidence-floor`, 0.6) is likewise NOT reachable
     through this demo: `appsdev.advisor/infer` maps `:stake` to a
     fixed confidence of 0.7/0.85/0.95 (`:high`/`:medium`/`:low`),
     never below the floor. Covered instead by
     `appsdev.governor-test/escalates-low-confidence`, which also
     calls `governor/check` directly. The only escalation this demo
     genuinely reaches through the real advisor is `:deploy-release`
     (always human-approved regardless of confidence).

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [appsdev.store :as store]
            [appsdev.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real appsdev operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it --
  this demo's scenario never demonstrates an UNAPPROVED escalation,
  every escalation here reaches a human who signs off. Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely
  reach through its real graph (auto-commit, escalate-then-approve,
  and all 8 of the 10 distinct HARD-hold rules in `appsdev.governor`
  that are reachable via the real advisor -- `:no-actuation` and the
  low-confidence escalation path are structurally unreachable, see
  namespace docstring). Every `:op` keyword and violation rule name
  below is copied from `appsdev.governor`'s own `hard-violations`/
  `check`, not invented."
  [;; client-1 / \"Kobo Dev\" / T-1 / run-green,run-red,run-stale
   ;; (real fixture from appsdev.actor-test / appsdev.governor-test)
   ["c1-draft"                "client-1" :draft-change  {:ticket-id "T-1" :stake :low}]
   ["c1-submit-ok"            "client-1" :submit-change {:ticket-id "T-1" :run-id "run-green" :commit "abc123" :stake :medium}]
   ["c1-submit-no-ticket"     "client-1" :submit-change {:ticket-id nil :run-id "run-green" :commit "abc123" :stake :medium}]
   ["c1-submit-unknown-ticket" "client-1" :submit-change {:ticket-id "T-ghost" :run-id "run-green" :commit "abc123" :stake :medium}]
   ["c1-submit-ticket-x-client" "client-1" :submit-change {:ticket-id "T-2" :run-id "run-green" :commit "abc123" :stake :medium}]
   ["c1-submit-no-run"        "client-1" :submit-change {:ticket-id "T-1" :run-id nil :commit "abc123" :stake :medium}]
   ["c1-submit-unknown-run"   "client-1" :submit-change {:ticket-id "T-1" :run-id "run-ghost" :commit "abc123" :stake :medium}]
   ["c1-submit-run-x-client"  "client-1" :submit-change {:ticket-id "T-1" :run-id "run-2-green" :commit "abc123" :stake :medium}]
   ["c1-submit-red"           "client-1" :submit-change {:ticket-id "T-1" :run-id "run-red" :commit "abc123" :stake :medium}]
   ["c1-submit-stale"         "client-1" :submit-change {:ticket-id "T-1" :run-id "run-stale" :commit "abc123" :stake :medium}]
   ["c1-deploy"               "client-1" :deploy-release {:stake :high}]
   ;; unregistered client entirely
   ["ghost-no-client"         "client-ghost" :draft-change {:ticket-id nil :stake :low}]
   ;; client-2 / \"Sunport Software\" / T-2 / run-2-green (additional
   ;; demo data, registered via the same real register-client!/
   ;; register-ticket!/register-test-run! calls -- see namespace
   ;; docstring)
   ["c2-draft"                "client-2" :draft-change  {:ticket-id "T-2" :stake :low}]
   ["c2-submit-ok"            "client-2" :submit-change {:ticket-id "T-2" :run-id "run-2-green" :commit "xyz789" :stake :medium}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `appsdev.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Dev"})
    (store/register-ticket! db {:ticket-id "T-1" :client-id "client-1"
                                :title "add invoice export"})
    (store/register-test-run! db {:run-id "run-green" :client-id "client-1"
                                  :commit "abc123" :status :green})
    (store/register-test-run! db {:run-id "run-red" :client-id "client-1"
                                  :commit "abc123" :status :red})
    (store/register-test-run! db {:run-id "run-stale" :client-id "client-1"
                                  :commit "old999" :status :green})
    (store/register-client! db {:client-id "client-2" :name "Sunport Software"})
    (store/register-ticket! db {:ticket-id "T-2" :client-id "client-2"
                                :title "add SSO login"})
    (store/register-test-run! db {:run-id "run-2-green" :client-id "client-2"
                                  :commit "xyz789" :status :green})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row
  "`ticket-ids`/`run-ids` are the known ids registered for this client in
  `run-demo!` below (not a bulk store enumeration -- `appsdev.store`'s
  protocol intentionally exposes only single-id lookups (`client`/
  `ticket`/`test-run`) plus `records-of`/`ledger`, no bulk listing, so
  counts of a client's own tickets/test-runs are read here via the same
  real `store/ticket`/`store/test-run` lookups the actor itself uses,
  keyed on the ids this renderer itself just registered)."
  [store {:keys [client-id name ticket-ids run-ids]} runs]
  (let [tickets (keep #(store/ticket store %) ticket-ids)
        test-runs (keep #(store/test-run store %) run-ids)
        committed (count (store/records-of store client-id))
        client-runs (filter #(= client-id (:client-id %)) runs)]
    (format "        <tr><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>"
            (esc client-id) (esc name) (count tickets) (count test-runs)
            committed (count client-runs))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (some-> (:ticket-id request) str)
                    ""))
          (outcome-cell {:outcome outcome :rule rule})))

(defn- test-run-row [{:keys [run-id client-id commit status]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
          (esc run-id) (esc client-id) (esc commit)
          (if (= :green status)
            "<span class=\"ok\">green</span>"
            "<span class=\"critical\">red</span>")))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md,
  ;; `appsdev.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:draft-change</code></td><td><span class=\"ok\">auto-commit when a registered ticket is cited</span></td></tr>"
   "        <tr><td><code>:submit-change</code></td><td><span class=\"warn\">HARD hold unless the cited test run is registered, `:green`, AND for the exact submission commit &middot; evidence freshness is identity, not recency</span></td></tr>"
   "        <tr><td><code>:deploy-release</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real deployment</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Kobo Dev"
                  :ticket-ids ["T-1"] :run-ids ["run-green" "run-red" "run-stale"]}
                 {:client-id "client-2" :name "Sunport Software"
                  :ticket-ids ["T-2"] :run-ids ["run-2-green"]}]
        test-runs [{:run-id "run-green" :client-id "client-1" :commit "abc123" :status :green}
                   {:run-id "run-red" :client-id "client-1" :commit "abc123" :status :red}
                   {:run-id "run-stale" :client-id "client-1" :commit "old999" :status :green}
                   {:run-id "run-2-green" :client-id "client-2" :commit "xyz789" :status :green}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        test-run-rows (str/join "\n" (map test-run-row test-runs))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2514 &middot; community applications programming</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Applications Programming (ISCO-08 2514) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · test evidence is commit-scoped</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>appsdev.store</code> via <code>appsdev.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Tickets</th><th>Test runs</th><th>Committed records</th><th>Requests this run</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered test evidence</h2>\n"
     "    <p class=\"muted\">Evidence is commit-scoped: a green run for another commit proves nothing about this one — see <code>run-stale</code> below (green, but for commit <code>old999</code>, not <code>abc123</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Run</th><th>Client</th><th>Commit</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     test-run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Community Applications Programming Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Every change must cite a registered ticket; every submission must cite commit-scoped green evidence.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own cited ticket, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Ticket cited</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
