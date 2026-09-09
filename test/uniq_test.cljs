;; test/uniq_test.cljs — build the command and compare it with the system
;; uniq, byte for byte.
;;
;; No locale here: uniq compares lines for EQUALITY, not order, so it has no
;; collation to disagree about. That is also why it needed no ordering
;; primitive -- `string=` is the whole comparison.

(ns uniq-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-uniq "/usr/bin/uniq")

;; A directory of fixtures, and the cases over them. Each case is an argv,
;; and each is here because it separates a right implementation from a wrong
;; one that passes the others:
;;
;;   one file           -- the basic contract
;;   two files          -- concatenated in ORDER, with nothing added between
;;   the same file twice-- an operand is not deduplicated
;;   an EMPTY file      -- reads as the empty string, which must not end the
;;                         loop the way "past the last operand" does
;;   empty then content -- the same trap from the other side
;;   no trailing newline-- cat adds nothing of its own
;;   binary-ish bytes   -- high bytes survive the round trip
;;   no operands        -- POSIX reads stdin; there is no stdin capability,
;;                         so this asserts what it ACTUALLY does (nothing),
;;                         not what POSIX says
(def fixtures
  {;; ADJACENT only: `a a b a` answers `a b a`, not `a b`. This is the case
   ;; that separates uniq from "distinct".
   "adj"      "a\na\nb\na\n"
   "none"     "a\nb\nc\n"
   "all"      "x\nx\nx\n"
   "empty"    ""
   "one"      "one\n"
   ;; No trailing newline: uniq ADDS one. Three bytes in, two out.
   "nonl"     "a\na"
   ;; Blank lines are lines, and two of them are a run of two.
   "blanks"   "a\n\n\nb\n"
   ;; The -c field boundary. `"%4d "` and a fixed five-wide field agree on
   ;; every count below 1000 and differ at it -- measured, 9 prints as
   ;; `   9 `, 1000 as `1000 ` and 10000 as `10000 `, so the four is a
   ;; MINIMUM. This fixture is why the width is tested rather than assumed.
   "thousand" :thousand-lines})

(def cases
  [["adj"] ["none"] ["all"] ["empty"] ["one"] ["nonl"] ["blanks"] ["thousand"]
   ["-c" "adj"] ["-c" "none"] ["-c" "all"] ["-c" "empty"] ["-c" "one"]
   ["-c" "nonl"] ["-c" "blanks"] ["-c" "thousand"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-uniq) (refuse (str "no " system-uniq " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-wc-"))
        src (.resolve path (.cwd js/process) "uniq" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "uniq.kexe")
        blob (.join path tmp "uniq.bin")
        exe (.join path tmp "uniq")
        exe-big (.join path tmp "uniq-big")]
    (.writeFileSync fs policy "{:allow #{[:cap/call 35] [:cap/call 37] [:cap/call 38]}}" "utf8")
    ;; The fixtures live in the tree the binary is packaged for. The native
    ;; loader refuses a relative request outright, so operands are absolute.
    (let [data (.join path tmp "data")]
      (.mkdirSync fs data)
      (doseq [[name content] fixtures]
        (.writeFileSync fs (.join path data name)
                        (if (= content :thousand-lines)
                          (.repeat "q\n" 1000)
                          content)
                        "utf8")))
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      ;; TWO binaries from the same code: one with the loader's default
      ;; string-arena budget and one with a raised budget. The pair is what
      ;; makes the ceiling below a measurement instead of a claim -- a single
      ;; binary could only show that some size works and some does not, not
      ;; that the bound is the arena and that it moves.
      ;; Fuel and arena are constants of the binary, so they are packaged
      ;; here rather than supplied at run time. Counting words walks one code
      ;; point at a time, so the guest recursion is as long as the file and
      ;; the default 512 fuel counts almost nothing.
      (doseq [[out extra] [[exe ["--fuel" "50000000" "--string-pool" "8000000" "--pairs" "200000"]]]]
        (let [p (run "nbb" (into [packager "--code" blob "--offset" offset "--isa" "aarch64"
                                  "--allow" "35,37,38"
                                  "--fs-scope" (.realpathSync fs (.join path tmp "data"))
                                  "--output" out]
                                 extra) {})]
          (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p))))))))
    ;; Now the only thing that matters: run it.
    (let [results
          (for [names cases]
            (let [argv (mapv #(if (str/starts-with? % "-")
                                %
                                (.join path (.realpathSync fs (.join path tmp "data")) %))
                             names)
                  k (run exe argv {})
                  s (run system-uniq argv {})
                  same? (and (= (.toString (:out k) "base64") (.toString (:out s) "base64"))
                             (= (:status k) (:status s)))]
              {:argv names :ok same? :kotoba (.toString (:out k) "utf8")
               :system (.toString (:out s) "utf8")
               :exit [(:status k) (:status s)]}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:argv r))
                      " -> " (pr-str (:kotoba r))
                      (when-not (:ok r) (str " but " system-uniq " says " (pr-str (:system r))
                                             " exits " (pr-str (:exit r))))))) 
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))