(ns appsdev.advisor
  "ApplicationsProgrammingAdvisor — proposes a programming operation
  (draft a change, submit a change, deploy a release) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `appsdev.governor` checks ticket basis and commit-scoped test
  evidence independently. Modeled on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :draft-change|:submit-change|:deploy-release
               :effect :propose :ticket-id str :run-id str-or-nil
               :commit str :stake kw :confidence n :rationale str}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake ticket-id run-id commit] :as request}]
  {:op op
   :effect :propose
   :ticket-id ticket-id
   :run-id run-id
   :commit commit
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an applications programming advisor. Given a request,
   propose an :op, the :ticket-id, the :commit and the citing :run-id,
   an honest :confidence and a :stake. Never claim tests are green —
   the governor checks the registered run for THIS commit.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
