(ns rep.core
  (:require
   [clojure.tools.cli :as cli]
   [nrepl.core :as nrepl])
  (:gen-class))

(defn- nrepl-port
  [opts]
  (let [dir (System/getProperty "user.dir")]
    (loop [option-value (:port (:options opts))]
      (if-some [[_ filename :as x] (re-matches #"^@(.*)" option-value)]
        (recur (slurp (str dir "/" filename)))
        (Long/parseLong option-value)))))

(defn- take-until
  "Transducer like take-while, except keeps the last value tested."
  [pred]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (let [result (rf result input)]
         (cond
          (reduced? result) result
          (pred input)      (reduced result)
          :else             result))))))

(defn- until-status
  "Process messages until we see one with a particular status."
  [status]
  (take-until #(contains? (into #{} (:status %)) status)))

(defn- effecting
  "Build an effectful transucer which operates on the `k` value in messages."
  [k effect-fn]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (when (contains? input k)
         (effect-fn (get input k)))
       (rf result input)))))

(defn- print-err
  [^String s]
  (let [^java.io.Writer w *err*]
    (.write w s)
    (.flush w)))

(defn- report-exceptions
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result input]
     (cond-> result
       (contains? input :ex)
       (assoc :exit-code 1)))))

(defn- null-reducer
  "A reducing function which does nothing."
  ([] nil)
  ([result] result)
  ([result input] result))

(def ^:private cli-options
  [["-p" "--port [HOST:]PORT|@FILE" "Connect to HOST at PORT, which may be read from FILE."
    :default "@.nrepl-port"]
   ["-h" "--help" "Show this help screen."]])

(defmulti command
  (fn [opts]
    (cond
      (:errors opts)          :syntax
      (:help (:options opts)) :help
      :else                   :eval)))

(defmethod command :syntax
  [{:keys [errors]}]
  (doseq [^String e errors]
    (.write ^java.io.Writer *err* (str e \newline)))
  (.flush ^java.io.Writer *err*)
  2)

(defmethod command :help
  [{:keys [summary]}]
  (println "rep: Single-shot nREPL client")
  (println "Syntax:")
  (println "  rep [OPTIONS] CODE ...")
  (println)
  (println "Options:")
  (println summary)
  (println)
  0)

(defmethod command :eval
  [{:keys [arguments] :as opts}]
  (let [conn (nrepl/connect :port (nrepl-port opts))
        client (nrepl/client conn 60000)
        session (nrepl/client-session client)
        msg-seq (session {:op "eval" :code (apply str arguments)})
        result (transduce
                 (comp
                   (until-status "done")
                   (effecting :out print)
                   (effecting :err print-err)
                   (effecting :value println)
                   report-exceptions)
                 null-reducer
                 {:exit-code 0}
                 msg-seq)
        ^java.io.Closeable cc conn]
    (.close cc)
    (:exit-code result)))

(defn rep
  [& args]
  (let [opts (cli/parse-opts args cli-options)]
    (command opts)))

(defn -main
  [& args]
  (System/exit (apply rep args)))
