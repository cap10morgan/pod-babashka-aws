(ns pod.babashka.aws.impl.aws.credentials
  (:require
   [clojure.data.json :as json]
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.tools.logging :as log]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.config :as config]
   [cognitect.aws.credentials :as creds]
   [cognitect.aws.util :as u]
   [pod.babashka.aws.impl.aws]))

;;; Pod Backend

 (def *providers (atom {}))

(defn create-provider [provider]
  (let [provider-id (java.util.UUID/randomUUID)]
    (swap! *providers assoc provider-id provider)
    {:provider-id provider-id}))

(defmacro with-system-properties [props & body]
  `(let [props# (System/getProperties)]
     (try
       (doseq [[k# v#] ~props]
         (System/setProperty k# v#))
       ~@body
       (finally
         (System/setProperties props#)))))

(defn get-provider [config]
  (get @*providers (get config :provider-id)))

(defn -basic-credentials-provider [conf]
  (create-provider (creds/basic-credentials-provider conf)))

(defn -environment-credentials-provider []
  (create-provider (creds/environment-credentials-provider)))

(defn -system-property-credentials-provider [jvm-props]
  (with-system-properties jvm-props
    (create-provider (creds/system-property-credentials-provider))))

(defn -profile-credentials-provider
  ([jvm-props]
   (with-system-properties jvm-props
     (create-provider (creds/profile-credentials-provider))))

  ([jvm-props profile-name]
   (with-system-properties jvm-props
     (create-provider (creds/profile-credentials-provider profile-name))))

  ([jvm-props profile-name ^java.io.File f]
   (throw (ex-info "profile-credentials-provider with 2 arguments not supported yet" {}))))

(defn run-credential-process-cmd [cmd]
  (let [{:keys [exit out err]} (shell/sh "bash" "-c" cmd)]
    (if (zero? exit)
      out
      (throw (ex-info (str "Non-zero exit: " (pr-str err)) {})))))

(defn get-credentials-via-cmd [cmd]
  (let [credential-map (json/read-str (run-credential-process-cmd cmd))
        {:strs [AccessKeyId SecretAccessKey SessionToken]} credential-map]
    (assert (and AccessKeyId SecretAccessKey))
    {"aws_access_key_id" AccessKeyId
     "aws_secret_access_key" SecretAccessKey
     "aws_session_token" SessionToken}))

(defn -profile-credentials-provider+
  "Like profile-credentials-provider but with support for credential_process

   See https://github.com/cognitect-labs/aws-api/issues/73"
  ([jvm-props]
   (with-system-properties jvm-props
     (-profile-credentials-provider+ jvm-props (or (u/getenv "AWS_PROFILE")
                                                   (u/getProperty "aws.profile")
                                                   "default"))))
  ([jvm-props profile-name]
   (with-system-properties jvm-props
     (-profile-credentials-provider+ jvm-props profile-name (or (io/file (u/getenv "AWS_CREDENTIAL_PROFILES_FILE"))
                                                                (io/file (u/getProperty "user.home") ".aws" "credentials")))))
  ([_jvm-props profile-name ^java.io.File f]
   (create-provider
    (creds/auto-refreshing-credentials
     (reify creds/CredentialsProvider
       (fetch [_]
         (when (.exists f)
           (try
             (let [profile (get (config/parse f) profile-name)
                   profile (if-let [cmd (get profile "credential_process")]
                             (merge profile (get-credentials-via-cmd cmd))
                             profile)]
               (creds/valid-credentials
                {:aws/access-key-id     (get profile "aws_access_key_id")
                 :aws/secret-access-key (get profile "aws_secret_access_key")
                 :aws/session-token     (get profile "aws_session_token")}
                "aws profiles file"))
             (catch Throwable t
               (log/error t "Error fetching credentials from aws profiles file")
               {})))))))))

(def http-client pod.babashka.aws.impl.aws/http-client)

(defn -default-credentials-provider [jvm-props]
   (with-system-properties jvm-props
     (create-provider (creds/default-credentials-provider @http-client))))

(extend-protocol creds/CredentialsProvider
  clojure.lang.PersistentArrayMap
  (fetch [m]
    (creds/fetch (get-provider m))))

;;; Pod Client

(defn -fetch [provider]
  (when-let [provider (get-provider provider)]
    (creds/fetch provider)))

(def lookup-map
  {'-fetch -fetch
   '-basic-credentials-provider -basic-credentials-provider
   '-system-property-credentials-provider -system-property-credentials-provider
   '-profile-credentials-provider -profile-credentials-provider
   '-profile-credentials-provider+ -profile-credentials-provider+
   '-default-credentials-provider -default-credentials-provider})

(def describe-map
  `{:name pod.babashka.aws.credentials
    :vars
    ~(conj (mapv (fn [[k _]]
                   {:name k})
                 lookup-map)
           {:name "fetch"
            :code (pr-str
                   '(defprotocol CredentialsProvider
                      (fetch [_])))}
           {:name "map->Provider"
            :code (pr-str
                   '(defrecord Provider [provider-id]
                      CredentialsProvider
                      (fetch [provider]
                        (-fetch provider))))}
           {:name "profile-credentials-provider"
            :code (pr-str
                   '(defn profile-credentials-provider [& args]
                      (map->Provider (apply -profile-credentials-provider (cons (System/getProperties) args)))))}

           {:name "profile-credentials-provider+"
            :code (pr-str
                   '(defn profile-credentials-provider+ [& args]
                      (map->Provider (apply -profile-credentials-provider+ (cons (System/getProperties) args)))))}

           {:name "basic-credentials-provider"
            :code (pr-str
                   '(defn basic-credentials-provider [conf]
                      (map->Provider (-basic-credentials-provider conf))))}

           {:name "system-property-credentials-provider"
            :code (pr-str
                   '(defn system-property-credentials-provider []
                      (map->Provider (-system-property-credentials-provider (System/getProperties)))))}

           {:name "default-credentials-provider"
            :code (pr-str
                   '(defn default-credentials-provider [& _]
                      (map->Provider (-default-credentials-provider (System/getProperties)))))})})
