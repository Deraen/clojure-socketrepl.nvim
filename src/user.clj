(ns user
  (:require
    [neovim-client.nvim :as nvim] ;; For repl convenience
    [socket-repl.socket-repl-plugin :as plugin]))

(defn go
  "Start the plugin."
  []
  (plugin/start true))
