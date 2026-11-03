(ns neorepl.xdg
  "Where neorepl keeps its files, following the XDG base directory spec."
  (:require [clojure.java.io :as io])
  (:import (java.io File)))

(defn base-dir
  "The base directory value, an XDG_*_HOME variable's, names when that's an
  absolute path, as the spec has it, or else default under home."
  ^File [value home default]
  (if (and value (.isAbsolute (io/file value)))
    (io/file value)
    (io/file home default)))

(defn- from-env ^File [var default]
  (base-dir (System/getenv var) (System/getProperty "user.home") default))

(defn cache-home ^File []
  (from-env "XDG_CACHE_HOME" ".cache"))

(defn state-home ^File []
  (from-env "XDG_STATE_HOME" ".local/state"))

(defn config-home ^File []
  (from-env "XDG_CONFIG_HOME" ".config"))
