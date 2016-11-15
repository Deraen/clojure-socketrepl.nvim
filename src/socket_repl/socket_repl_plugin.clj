(ns socket-repl.socket-repl-plugin
  "A plugin which connects to a running socket repl and sends output back to
  Neovim."
  (:require
    [clojure.core.async :as async :refer [go go-loop >! <!]]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [neovim-client.message :as message]
    [neovim-client.nvim :as nvim])
  (:import
    (java.net Socket)
    (java.io PrintStream File))
  (:gen-class))

(def current-connection (atom nil))

(defn position
  "Find the position in a code string given line and column."
  [code-str [y x]]
  (->> code-str
       string/split-lines
       (take (dec y))
       (string/join "\n")
       count
       (+ (inc x))))

(defn get-form-at
  "Returns the enclosing form from a string a code using [row col]
  coordinates."
  [code-str coords]
  (let [pos (position code-str coords)]
    (read-string
      ;; Start at the last index of paren on or before `pos`, read a form.
      (subs code-str (if (= \( (.charAt code-str pos))
                       pos
                       (.lastIndexOf (subs code-str 0 pos) "("))))))

(defn output-file
  []
  (File/createTempFile "socket-repl" ".txt"))

(defn connection
  "Create a connection to a socket repl."
  [host port]
  (let [socket (java.net.Socket. host (Integer/parseInt port))]
    {:host host
     :port port
     :out (-> socket
              io/output-stream
              PrintStream.)
     :in (io/reader socket)}))

(defn write-output
  "Write a string to the output file."
  [{:keys [:file-stream]} string]
  (.print file-stream string)
  (.flush file-stream))

(defn write-output!
  "Like `write-output`, but uses the current socket repl connection."
  [string]
  (write-output @current-connection string))

(defn write-error!
  "Write a throwable's stack trace to the output file, using the current socket
  repl connection."
  [throwable]
  (write-output!
    (str "\n##### PLUGIN ERR #####\n"
         (.getMessage throwable) "\n"
         (string/join "\n" (map str (.getStackTrace throwable)))
         \n"######################\n")))

(defn write-code
  "Writes a string of code to the socket repl connection."
  [{:keys [:out]} code-string]
  (.println out code-string)
  (.flush out))

(defn write-code!
  "Like `write-code`, but uses the current socket repl connection."
  [code-string]
  (write-output @current-connection (str code-string "\n"))
  (write-code @current-connection code-string))

(defn update-last!
  "Update the last accessed time."
  []
  (swap! current-connection assoc :last (System/currentTimeMillis)))

(defn connected-to-socket-repl?
  "Returns true if currently connected to a socket repl server."
  [connection]
  ;; Choose one of the properties set by establishing the connection.
  (:host connection))

(defn warn-if-disconnect*
  [connection-atom f]
  (fn [msg]
    (if-not (connected-to-socket-repl? @connection-atom)
      (nvim/vim-command-async
        ":echo 'Use :Connect host:port to connect to a socket repl'"
        (fn [_]))
      (f msg))
    :done))

(defn warn-if-disconnect
  "Warn the user if not connected to a socket repl."
  [f]
  (warn-if-disconnect* current-connection f))

(defn get-rlog-buffer-async
  "Returns a channel which contains the name of the buffer w/ b:rlog set, if
  one exists."
  []
  (->> (nvim/vim-get-buffers)
       (filter #(nvim/buffer-get-var % "rlog"))
       (map nvim/buffer-get-name)
       first
       go))

(defn connect!
  "Connect to a socket repl. Adds the connection to the `current-connection`
  atom. Creates `go-loop`s to delegate input from the socket to `handler` one
  line at a time.

  `handler` is a function which accepts one string argument."
  [host port handler]
  (let [conn (connection host port)
        chan (async/chan 1024)
        file (output-file)]
    (reset! current-connection
            (assoc conn
                   :handler handler
                   :chan chan
                   :file file
                   :file-stream (PrintStream. file)
                   :last (System/currentTimeMillis)))

    ;; input producer
    (go-loop []
             (when-let [line (str (.readLine (:in conn)) "\n")]
               (>! chan line)
               (recur)))

    ;; input consumer
    (go-loop []
             (when-let [x (<! chan)]
               (handler x)
               (recur))))
  "success")

(defn start
  [debug]
  (if debug
    (nvim/connect! "localhost" 7777)
    (nvim/connect!))

  (nvim/register-method!
    "connect"
    (fn [msg]
      (let [[host port] (-> msg
                            message/params
                            first
                            (string/split #":"))]
        (update-last!)
        (try
          (connect! host port
                    (fn [x]
                      (write-output! x)))
          (catch Throwable t
            (log/error t "Error connecting to socket repl")
            (nvim/vim-command-async
              ":echo 'Unable to connect to socket repl.'"
              (fn [_])))))))

  (nvim/register-method!
    "eval-code"
    (warn-if-disconnect
      (fn [msg]
        (update-last!)
        (go
          (let [coords (nvim/get-cursor-location)
                buffer-text (nvim/get-current-buffer-text)]
            (try
              (write-code! (get-form-at buffer-text coords))
              (catch Throwable t
                (log/error t "Error evaluating a form")
                (write-error! t))))))))

  (nvim/register-method!
    "eval-buffer"
    (warn-if-disconnect
      (fn [msg]
        (update-last!)
        (go
          (let [buffer (nvim/vim-get-current-buffer)
                filename (nvim/buffer-get-name buffer)]
            (if (.exists (io/as-file filename))
              (do
                ;; Not sure if saving the file is really always what we want,
                ;; but if we don't, stale data will be loaded.
                (nvim/vim-command ":w")
                (write-code! (format "(load-file \"%s\")" filename)))
              (let [code (string/join "\n" (nvim/buffer-get-line-slice
                                             buffer 0 -1))]
                (write-code! (format "(eval '(do %s))" code)))))))))

  (nvim/register-method!
    "doc"
    (warn-if-disconnect
      (fn [msg]
        (nvim/get-current-word-async
          (fn [word]
            (let [code (format "(clojure.repl/doc  %s)" word)]
              (write-code! code)))))))

  (nvim/register-method!
    "show-log"
    (warn-if-disconnect
      (fn [msg]
        (update-last!)
        (let [file (-> @current-connection :file .getAbsolutePath)]
          (go
            (let [buffer-cmd (first (message/params msg))
                  rlog-buffer (<! (get-rlog-buffer-async))
                  rlog-buffer-visible? (when rlog-buffer
                                         (<! (nvim/buffer-visible?-async
                                               rlog-buffer)))]
              (when-not rlog-buffer-visible?
                (nvim/vim-command-async
                  (format "%s | nnoremap <buffer> q :q<cr> | :let b:rlog=1 | :call termopen('tail -f %s')"
                          buffer-cmd file)
                  (fn [_])))))
          ;; Don't return a core.async channel, else msgpack will fail to
          ;; serialize it.
          "success"))))

  ;; Don't need to do this in debug, socket repl will keep this alive.
  (when-not debug
    (loop []
      (Thread/sleep 30000)
      (let [elapsed-msec (- (System/currentTimeMillis)
                            (:last @current-connection))]
        (when (< elapsed-msec (* 10 60000))
          (recur))))

    ;; Let nvim know we're shutting down.
    (nvim/vim-command ":let g:is_running=0")
    (nvim/vim-command ":echo 'plugin stopping.'")))

(defn -main
  [& args]
  (start false))
