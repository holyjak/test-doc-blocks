#!/usr/bin/env bb

;;
;; This script is ultimately run from GitHub Actions
;; 

(ns release
  (:require [babashka.classpath :as cp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(cp/add-classpath (.getParent (io/file *file*)))

(require '[helper.fs :as fs]
         '[helper.shell :as shell]
         '[helper.status :as status])

(defn clean []
  (doseq [dir ["target" ".cpcache"]]
    (fs/delete-file-recursively dir true)))

(defn- last-release-tag []
  (->  (shell/command ["git" "describe" "--abbrev=0" "--match" "v[0-9]*"] {:out :string})
       :out
       string/trim))

(defn- repo-commit-count
  "Number of commits in the repo"
  []
  (->  (shell/command ["git" "rev-list" "HEAD" "--count"] {:out :string})
       :out
       string/trim
       Long/parseLong))

(defn- dev-specified-version []
  (-> "version.edn"
      slurp
      edn/read-string))

(defn- calculate-version []
  (status/line :info "Calculating release version")
  (let [version-template (dev-specified-version)
        patch (repo-commit-count)
        version (str (:major version-template) "." 
                     (:minor version-template) "."
                     patch
                     (cond->> (:qualifier version-template)
                       true (str "-")))]
    (status/line :detail (str "version: " version))
    version))

(defn- update-file! [fname match replacement]
  (let [old-content (slurp fname)
        new-content (string/replace-first old-content match replacement)]
    (if (= old-content new-content)
      (status/fatal (str  "Expected " fname " to be updated."))
      (spit fname new-content))))

(defn- update-readme! [version]
  (status/line :info (str "Updating README usage to show version " version))
  (update-file! "README.adoc"
                #"( +\{:extra-deps \{lread/test-doc-blocks \{:mvn/version \").*(\"\}\})"
                (str  "$1" version "$2")))

(defn- adoc-section-search[content find-section]
  (cond
    (re-find (re-pattern  (str  "(?ims)^=+ " find-section " *$\\s+(^=|\\z)")) content)
    :found-with-no-text

    (re-find (re-pattern  (str  "(?ims)^=+ " find-section " *$")) content)
    :found
    
    :else
    :not-found))

(defn- validate-changelog 
  "Certainly not fool proof, but should help for common mistakes"
  []
  (status/line :info "Validating change log")
  (let [content (slurp "CHANGELOG.adoc")
        unreleased-status (adoc-section-search content "Unreleased")
        unreleased-breaking-status (adoc-section-search content "Unreleased Breaking Changes")]
    (case unreleased-status
      :found (status/line :detail "✅ Unreleased section found with descriptive text.")
      :found-with-no-text (status/line :detail "❌ Unreleased section seems empty, please put some descriptive text in there to help our users understand what is in the release.")
      :not-found (status/line :detail "❌ Unreleased section not found, please add one."))
    (case unreleased-breaking-status
      :found (status/line :detail "✅ Unreleased Breaking Changes section found with descriptive text")
      :found-with-no-text (status/line :detail (str "❌ Unreleased breaking changes section found but seems empty.\n"
                                                    "   Please put some descriptive text in there to help our users understand what is in the release\n"
                                                    "   OR delete the section if there are no breaking changes for this release."))
      :not-found (status/line :detail "✅ Unreleased Breaking Changes section not found, assuming no breaking changes for this release."))
      
    (if (or (not (= :found unreleased-status))
            (= :found-with-no-text unreleased-breaking-status))
      (status/fatal "Changelog needs some love")
      (status/line :detail "Changelog looks good for update by release workflow."))
    {:unreleased unreleased-status
     :unreleased-breaking unreleased-breaking-status}))

(defn- update-changelog! [version last-version {:keys [unreleased-breaking]}]
  (status/line :info (str "Updating Change Log unreleased headers to release " version))
  (update-file! "CHANGELOG.adoc"
                #"(?ims)^(=+) +unreleased *$(.*?)(^=+)"
                (str "$1 Unreleased\n\n$1 v" version "$2"
                     (when last-version
                       (str
                        "https://github.com/lread/test-doc-blocks/compare/"
                        last-version
                        "\\\\..."  ;; single backslash is escape for AsciiDoc
                        version
                        "[Gritty details of changes for this release]\n\n"))
                     "$3"))
  (when (= :found unreleased-breaking)
    (update-file! "CHANGELOG.adoc"
                  #"(?ims)(=+) +unreleased breaking changes *$"
                  (str "$1 v" version))))

(defn- create-jar! [version]
  (status/line :info (str "Updating pom.xml and creating jar for version " version))
  (shell/command ["clojure" "-X:jar" ":version" (pr-str version)])
  nil)

(defn- assert-on-ci
  "Little blocker to save myself from myself when testing."
  [action]
  (when (not (System/getenv "CI"))
    (status/fatal (format  "We only want to %s from CI" action))))

(defn- deploy-jar!
  "For this to work, appropriate CLOJARS_USERNAME and CLOJARS_PASSWORD must be in environment."
  []
  (status/line :info "Deploying jar to clojars")
  (assert-on-ci "deploy a jar")
  (shell/command ["clojure" "-X:deploy"])
  nil)

(defn- commit-changes! [version]
  (let [tag-version (str "v" version)]
    (status/line :info (str  "Committing and pushing changes made for " tag-version))
    (assert-on-ci "commit changes")
    (status/line :detail "Adding changes")
    (shell/command ["git" "add" "README.adoc" "CHANGELOG.adoc" "pom.xml"])
    (status/line :detail "Committing")
    (shell/command ["git" "commit" "-m" (str  "Release job: updates for version " tag-version)])
    (status/line :detail "Version tagging")
    (shell/command ["git" "tag" "-a" tag-version "-m" (str  "Release " tag-version)])
    (status/line :detail "Pushing commit")
    (shell/command ["git" "push"])
    (status/line :detail "Pushing version tag")
    (shell/command ["git" "push" "origin" tag-version])
    nil))

(defn- inform-cljdoc! [version]
  (status/line :info (str "Informing cljdoc of new version " version))
  (assert-on-ci "inform cljdoc")
  (let [exit-code (->  (shell/command-no-exit ["curl" "-X" "POST"
                                               "-d" "project=lread/test-doc-blocks"
                                               "-d" (str  "version=" version)
                                               "https://cljdoc.org/api/request-build2"])
                       :exit)]
    (when (not (zero? exit-code))
      (status/line :warn (str  "Informing cljdoc did not seem to work, exited with " exit-code)))))

(defn- main []
  (status/line :info "Attempting release")
  (clean)
  (let [changelog-status (validate-changelog)
        target-version (calculate-version)
        last-version (last-release-tag)]
    (status/line :detail (str "Last version released: " (or last-version "<none>")))
    (status/line :detail (str "Target version:        " target-version))
    (update-readme! target-version)
    (update-changelog! target-version last-version changelog-status)
    (create-jar! target-version)
    (deploy-jar!)
    (commit-changes! target-version)
    (inform-cljdoc! target-version)
    (status/line :detail "Release work done.")))

(main)
