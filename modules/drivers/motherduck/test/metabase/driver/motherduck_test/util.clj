(ns metabase.driver.motherduck-test.util
  "A shared test helper for the MotherDuck token. The driver tests
  (`metabase.driver.motherduck-test`) use this helper. The test data extension
  (`metabase.test.data.motherduck`) also uses it. Both need the same credential for the live
  MotherDuck pg endpoint."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-dotenv
  "Read the KEY=VALUE lines of the `.env` content. Give a map of string to string."
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
  "Read the `.env` file in the repository root and give a map. Give an empty map if the file does not
  exist. The working directory of the test runner is the repository root."
  []
  (let [f (io/file ".env")]
    (if (.exists f)
      (parse-dotenv (slurp f))
      {})))

(defn motherduck-token
  "Give the MotherDuck token. Read the `MOTHERDUCK_TOKEN` environment variable first. Then read a
  `MOTHERDUCK_TOKEN=` line in the `.env` file in the repository root. The MotherDuck pg gateway uses
  this token as the Postgres password."
  []
  (or (not-empty (System/getenv "MOTHERDUCK_TOKEN"))
      (not-empty (get (dotenv) "MOTHERDUCK_TOKEN"))))
