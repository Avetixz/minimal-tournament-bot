(ns minimal-tournament-bot.core
  (:require [clojure.core.async :refer [chan close!]]
            [discljord.messaging :as discord-rest]
            [discljord.connections :as discord-ws]
            [discljord.events :refer [message-pump!]]
            [slash.core :as sc]
            [slash.command :as scmd]
            [slash.command.structure :as scs]
            [slash.response :as srsp]
            [slash.gateway :as sg]
            [slash.component.structure :as scomp]
            [clj-http.client :as client]))

(def bot-token "FIXME")
(def guild-id "FIXME")

(def state (atom nil))
(def bot-id (atom nil))
(def num-signups (atom 0))

(defn get-signup-message [] (str "New tournament created\nSignups: " @num-signups))

(def open-command
  (scs/command
   "open"
   "Open a new tournament"))

(scmd/defhandler open-handler
  ["open"]
  interaction
  []
  (let [components [(scomp/action-row
                     (scomp/button :success "sign-up" :label "Sign up"))]]
    (srsp/channel-message {:content (get-signup-message) :components components})))

(scmd/defpaths command-paths #'open-handler)

;; Component interactions
(defmulti handle-component-interaction
  (fn [interaction] (-> interaction :data :custom-id)))

(defmethod handle-component-interaction "sign-up"
  [interaction]
  (swap! num-signups inc)
  (srsp/update-message {:content (get-signup-message)}))

;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command command-paths
         :message-component #'handle-component-interaction))

(defmulti handle-event (fn [type _data] type))

(defn- create-interaction-response! [rest-conn interaction-id token type & {:as opts}]
  (let [url (str "https://discord.com/api/v10/interactions/" interaction-id "/" token "/callback")
        json (assoc opts :type type)]
    (client/post url {:as :auto
                      :coerce :always
                      :content-type :json
                      :form-params json})))

(defmethod handle-event :interaction-create
  [_ event-data]
  (let [{:keys [type data]} (sc/route-interaction interaction-handlers event-data)]
    (discord-rest/create-interaction-response! (:rest @state) (:id event-data) (:token event-data) type :data data)))

(defmethod handle-event :default [_ _])

(defn start-bot! [token & intents]
  (let [event-channel (chan 100)
        gateway-connection (discord-ws/connect-bot! token event-channel :intents (set intents))
        rest-connection (discord-rest/start-connection! token)]
    {:events  event-channel
     :gateway gateway-connection
     :rest    rest-connection}))

(defn stop-bot! [{:keys [rest gateway events] :as _state}]
  (discord-rest/stop-connection! rest)
  (discord-ws/disconnect-bot! gateway)
  (close! events))

(defn -main [& args]
  (reset! state (start-bot! bot-token :guild-messages))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state))))
  (discord-rest/bulk-overwrite-guild-application-commands! (:rest @state) @bot-id guild-id [open-command])
  (try
    (message-pump! (:events @state) handle-event)
    (finally (stop-bot! @state))))
