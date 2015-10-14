(ns onyx.state.log.bookkeeper
  (:require [onyx.log.curator :as curator]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]
            [com.stuartsierra.component :as component]
            [clojure.core.async :refer  [chan go >! <! <!! >!! alts!! close!]]
            [onyx.compression.nippy :as nippy]
            [onyx.extensions :as extensions]
            [onyx.monitoring.measurements :refer [emit-latency-value emit-latency]]
            [onyx.peer.operation :as operation]
            [onyx.state.state-extensions :as state-extensions]
            [onyx.types :refer [inc-count! dec-count!]]
            [onyx.log.replica]
            [onyx.log.commands.common :refer [peer-slot-id]]
            [onyx.log.commands.assign-bookkeeper-log-id]
            [onyx.log.zookeeper :as zk]
            [onyx.static.default-vals :refer [arg-or-default defaults]])
  (:import [org.apache.bookkeeper.client LedgerHandle LedgerEntry BookKeeper BookKeeper$DigestType AsyncCallback$AddCallback]
           [org.apache.bookkeeper.conf ClientConfiguration]
           [org.apache.curator.framework CuratorFramework CuratorFrameworkFactory]))

(defrecord BookKeeperLog [client ledger-handle next-ledger-handle])

(defn open-ledger ^LedgerHandle [^BookKeeper client id digest-type password]
  (.openLedger client id digest-type password))

(defn create-ledger ^LedgerHandle [^BookKeeper client ensemble-size quorum-size digest-type password]
  (.createLedger client ensemble-size quorum-size digest-type password))

(defn bookkeeper
  ([opts]
   (bookkeeper (:zookeeper/address opts)
               (zk/ledgers-path (:onyx/id opts))
               (arg-or-default :onyx.bookkeeper/client-timeout opts)
               (arg-or-default :onyx.bookkeeper/client-throttle opts)))
  ([zk-addr zk-root-path timeout throttle]
   (let [conf (doto (ClientConfiguration.)
                (.setZkServers zk-addr)
                (.setZkTimeout timeout)
                (.setThrottleValue throttle)
                (.setZkLedgersRootPath zk-root-path))]
     (BookKeeper. conf))))

(def digest-type 
  (BookKeeper$DigestType/MAC))

(defn password [peer-opts]
  (.getBytes ^String (arg-or-default :onyx.bookkeeper/ledger-password peer-opts)))


(defn new-ledger [client peer-opts]
  (let [ensemble-size (arg-or-default :onyx.bookkeeper/ledger-ensemble-size peer-opts)
        quorum-size (arg-or-default :onyx.bookkeeper/ledger-quorum-size peer-opts)]
    (create-ledger client ensemble-size quorum-size digest-type (password peer-opts))))

(defmethod state-extensions/initialize-log :bookkeeper [log-type {:keys [onyx.core/replica onyx.core/peer-opts
                                                                         onyx.core/job-id onyx.core/task-id
                                                                         onyx.core/kill-ch onyx.core/task-kill-ch
                                                                         onyx.core/outbox-ch] :as event}] 
  (let [bk-client (bookkeeper peer-opts)
        ledger-handle (new-ledger bk-client peer-opts)
        slot-id (peer-slot-id event)
        new-ledger-id (.getId ledger-handle)
        next-ledger-handle nil] 
    (>!! outbox-ch
         {:fn :assign-bookkeeper-log-id
          :args {:job-id job-id
                 :task-id task-id
                 :slot-id slot-id
                 :ledger-id new-ledger-id}})
    (while (and (first (alts!! [kill-ch task-kill-ch] :default true))
                (not= new-ledger-id
                      (last (get-in @replica [:state-logs job-id task-id slot-id]))))
      (info "New ledger id has not been published yet. Backing off.")
      (Thread/sleep (arg-or-default :onyx.bookkeeper/ledger-id-written-back-off peer-opts))) 
    (info "Ledger id published.")
    (->BookKeeperLog bk-client (atom ledger-handle) (atom next-ledger-handle))))

(defn default-state 
  "Default state function. Resolves window late for perf."
  [state window]
  (if state
    state
    ((:aggregate/init window) window)))

(defn playback-windows-extents [state entry windows]
  (let [id->apply-state-update (into {} 
                                     (map (juxt :window/id :aggregate/apply-state-update) 
                                          windows))] 
    (reduce (fn [state' [window-entries {:keys [window/id] :as window}]]
              (reduce (fn [state'' [extent entry message-id]]
                        (update-in state'' 
                                   [id extent]
                                   (fn [ext-state] 
                                     (let [ext-state' (default-state ext-state window)
                                           apply-fn (id->apply-state-update id)] 
                                       (assert apply-fn (str "Apply fn does not exist for window-id " id))
                                       (apply-fn ext-state' entry)))))
                      state'
                      window-entries)) 
            state
            (map list (rest entry) windows)))) 

(defn playback-ledgers [bk-client peer-opts state ledger-ids {:keys [onyx.core/windows] :as event}]
  (let [pwd (password peer-opts)] 
    (reduce (fn [st ledger-id]
              ;; TODO: Do I need to deal with recovery exception in here?
              ;; It may be better to just let the thing crash and retry
              (let [lh (open-ledger bk-client ledger-id digest-type pwd)]
                (try
                  (let [last-confirmed (.getLastAddConfirmed lh)
                        _ (info "Opened ledger:" ledger-id "last confirmed:" last-confirmed)]
                    (if (pos? last-confirmed)
                      (let [entries (.readEntries lh 0 last-confirmed)] 
                        (if (.hasMoreElements entries)
                          (loop [st-loop st element ^LedgerEntry (.nextElement entries)]
                            (let [entry-val (nippy/window-log-decompress ^bytes (.getEntry element))
                                  unique-id (first entry-val)
                                  st-loop' (let [st (playback-windows-extents st-loop entry-val windows)]
                                             (if unique-id
                                               (update st :filter state-extensions/apply-filter-id event unique-id)
                                               st))] 
                              (info "Played back entries for message with id: " unique-id)
                              (if (.hasMoreElements entries)
                                (recur st-loop' (.nextElement entries))
                                st-loop')))
                          st))  
                      st))
                  (finally
                    (.close ^LedgerHandle lh)))))
            state
            ledger-ids)))


(defmethod state-extensions/playback-log-entries onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client] :as log} 
   {:keys [onyx.core/monitoring onyx.core/replica 
           onyx.core/peer-opts onyx.core/job-id onyx.core/task-id] :as event} 
   state]
  (emit-latency :window-log-playback 
                monitoring
                (fn [] 
                  (let [slot-id (peer-slot-id event)
                        ;; Don't play back the final ledger id because we just created it
                        prev-ledger-ids (butlast (get-in @replica [:state-logs job-id task-id slot-id]))]
                    (info "Playing back ledgers for" job-id task-id slot-id "ledger-ids" prev-ledger-ids)
                    (playback-ledgers client peer-opts state prev-ledger-ids event)))))

(defmethod state-extensions/compact-log onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client ledger-handle next-ledger-handle]} 
   {:keys [onyx.core/replica onyx.core/peer-opts
           onyx.core/job-id onyx.core/task-id
           onyx.core/kill-ch onyx.core/task-kill-ch
           onyx.core/outbox-ch] :as event} 
   _] 
  (let [new-ledger-handle (new-ledger client peer-opts)
        slot-id (peer-slot-id event)
        new-ledger-id (.getId new-ledger-handle)] 
    (>!! outbox-ch
         {:fn :assign-bookkeeper-log-id
          :args {:job-id job-id
                 :task-id task-id
                 :slot-id slot-id
                 :ledger-id new-ledger-id}})
    (while (and (first (alts!! [kill-ch task-kill-ch] :default true))
                (not= new-ledger-id
                      (last (get-in @replica [:state-logs job-id task-id slot-id]))))
      (info "Transitional GC ledger id has not been published yet. Backing off.")
      (Thread/sleep (arg-or-default :onyx.bookkeeper/ledger-id-written-back-off peer-opts)))
    (reset! next-ledger-handle new-ledger-handle)))

(defmethod state-extensions/close-log onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client ledger-handle next-ledger-handle]} event] 
  (.close ^LedgerHandle @ledger-handle)
  (when @next-ledger-handle
    (.close ^LedgerHandle @next-ledger-handle))
  (.close ^BookKeeper client))

(def HandleWriteCallback
  (reify AsyncCallback$AddCallback
    (addComplete [this rc lh entry-id callback-fn]
      (callback-fn))))

(defn gc-transition 
  "Transitions to a new compacted ledger, plus a newly created ledger created
  earlier.  For example, if there were ledgers [1, 2, 3, 4], we've created a
  ledger id 5 to start writing to, making [1, 2, 3, 4, 5], then we create a compacted
  ledger 6, write the updated state to it, and swap [1, 2, 3, 4] in the replica
  for 6, leaving [6, 5]"
  [{:keys [client ledger-handle next-ledger-handle] :as log}
   {:keys [onyx.core/peer-opts onyx.core/job-id onyx.core/replica
           onyx.core/task-id onyx.core/window-state onyx.core/outbox-ch] 
    :as event}]
  (info "Transitioning to new handle after gc" (.getId @next-ledger-handle))
  (reset! ledger-handle @next-ledger-handle)
  (reset! next-ledger-handle nil)
  (let [slot-id (peer-slot-id event)
        window-state-snapshot (:state @(:onyx.core/window-state event))
        current-ids (get-in @replica [:state-logs job-id task-id slot-id])]
    ;; Don't throw an exception, maybe we can give the next GC a chance to succeed
    (if-not (= (last current-ids) (.getId @ledger-handle))
      (warn "Could not swap compacted log. Next ledger handle is no longer the next published ledger" 
            {:job-id job-id :task-id task-id :slot-id slot-id :current-ids current-ids})
      (future (let [compacted-ledger (new-ledger client peer-opts)
                    compacted-ledger-id (.getId compacted-ledger)]
                (info "Snapshotted state " window-state-snapshot " putting in " compacted-ledger-id)
                (.asyncAddEntry ^LedgerHandle compacted-ledger 
                                ^bytes (nippy/window-log-compress window-state-snapshot)
                                HandleWriteCallback
                                (fn []
                                  (info "Wrote log message out!")
                                  (>!! outbox-ch
                                       {:fn :compact-bookkeeper-log-ids
                                        :args {:job-id job-id
                                               :task-id task-id
                                               :slot-id slot-id
                                               :prev-ledger-ids (vec (butlast current-ids))
                                               :new-ledger-ids [compacted-ledger-id]}}))))))))

(defmethod state-extensions/store-log-entry onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [ledger-handle next-ledger-handle] :as log} event ack-fn entry]
  (when @next-ledger-handle
    (gc-transition log event))
  (.asyncAddEntry ^LedgerHandle @ledger-handle 
                  ^bytes (nippy/window-log-compress entry)
                  HandleWriteCallback
                  ack-fn))