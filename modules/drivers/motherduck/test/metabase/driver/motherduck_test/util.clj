(ns metabase.driver.motherduck-test.util
  "Shared token lookup for MotherDuck connection tests and test data loading."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-dotenv
  "Parse KEY=VALUE lines into a map of strings, skipping blank lines and comments."
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
  "Read .env from the test runner's working directory, the repository root.
  Return an empty map if the file does not exist."
  []
  (let [f (io/file ".env")]
    (if (.exists f)
      (parse-dotenv (slurp f))
      {})))

(defn motherduck-token
  "Return MOTHERDUCK_TOKEN from the environment, falling back to .env in the repository root.
  The MotherDuck Postgres endpoint uses this token as the Postgres password."
  []
  (or (not-empty (System/getenv "MOTHERDUCK_TOKEN"))
      (not-empty (get (dotenv) "MOTHERDUCK_TOKEN"))))
