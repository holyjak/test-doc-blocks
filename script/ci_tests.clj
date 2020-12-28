#!/usr/bin/env bb

(ns ci-tests
  (:require [babashka.classpath :as cp]))

(cp/add-classpath "./script")
(require '[helper.env :as env]
         '[helper.fs :as fs]
         '[helper.shell :as shell]
         '[helper.status :as status])

(defn clean []
  (doseq [dir ["target" ".cpcache"]]
    (fs/delete-file-recursively dir true)))

(defn lint[]
  (shell/command ["bb" "./script/lint.clj"]))

(defn integration-tests []
  (status/line :info "Running test-doc-blocks integration tests")
  (shell/command ["clojure" "-M:test:kaocha" "integration" ]) )

(defn generate-tests []
  (status/line :info "Generating tests from local docs")
  (shell/command ["clojure" "-X:gen-local-tests"]))

(defn run-generated-tests []
  (status/line :info "Running locally generated tests under Clojure via kaocha")
  (shell/command ["clojure" "-M:block-test:kaocha" "generated"])

  (status/line :info "Running locally generated tests under Clojure via Clojure test-runner")
  (shell/command ["clojure" "-M:block-test:clj-test-runner"])

  (status/line :info "Running locally generated tests under ClojureScript via cljs-test-runner")
  (shell/command ["clojure" "-M:block-test:cljs-test-runner"]))

(defn main[]
  (env/assert-min-versions)
  (clean)
  (lint)
  (integration-tests)
  (generate-tests)
  (run-generated-tests)
  nil)

(main)
