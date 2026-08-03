(ns metabase.driver.motherduck-test.util
  "Shared test helper for the MotherDuck token, used both by the driver's own tests
  (`metabase.driver.motherduck-test`) and by the test-data extension (`metabase.test.data.motherduck`) — both need
  the same credential to reach the live MotherDuck pg endpoint."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-dotenv
  "Parse simple KEY=VALUE lines from `.env` content into a map of string->string."
  [content]
  (into {}
        (for [line  (str/split-lines content)
              :let  [line (str/trim line)]
              :when (and (seq line)
                         (not (str/starts-with? line "#"))
                         (str/includes? line "="))
              :let  [[k v] (str/split line #"=" 2)]]
          [(str/trim k) (str/trim v)])))

(defn- dotenv
  "Read the repo-root `.env` into a map (empty map if it doesn't exist). The test runner's working
  directory is the repo root."
  []
  (let [f (io/file ".env")]
    (if (.exists f)
      (parse-dotenv (slurp f))
      {})))

(defn motherduck-token
  "The MotherDuck token: `MOTHERDUCK_TOKEN` env var, then a `MOTHERDUCK_TOKEN=` line in repo-root `.env`. The
  MotherDuck pg gateway authenticates with the token as the Postgres password."
  []
  (or (not-empty (System/getenv "MOTHERDUCK_TOKEN"))
      (not-empty (get (dotenv) "MOTHERDUCK_TOKEN"))))
