(ns memento.guava
  "Guava cache implementation."
  (:require [memento.base :as b])
  (:import (com.google.common.cache Cache CacheBuilder Weigher RemovalListener)
           (java.util.concurrent TimeUnit ExecutionException)
           (com.google.common.base Ticker)
           (com.google.common.util.concurrent UncheckedExecutionException)
           (memento.base NonCached)))

(def timeunits
  {:ns TimeUnit/NANOSECONDS
   :us TimeUnit/MICROSECONDS
   :ms TimeUnit/MILLISECONDS
   :s TimeUnit/SECONDS
   :m TimeUnit/MINUTES
   :h TimeUnit/HOURS
   :d TimeUnit/DAYS})

(defn parse-time-scalar
  "Returns the scalar part of time spec. Time can be specified by integer
  or a vector of two elements, where first element is an integer and the other is
  the time unit keyword."
  [time-param]
  (if (number? time-param) (long time-param) (first time-param)))

(defn ^TimeUnit parse-time-unit
  "Returns the time unit part of time spec. Time can be specified by integer
  or a vector of two elements, where first element is an integer and the other is
  the time unit keyword. If only integer is specified then time unit is seconds."
  [time-param]
  (timeunits (if (number? time-param) :s (second time-param))))

(defn cval->val "Change ::nil cache value to nil" [v]
  (when-not (= v ::nil) v))

(defn val->cval "Change nil to cache value of ::nil" [v]
  (if (some? v) v ::nil))

(defn process-non-cached
  "Unwrap NonCached objects and throw exception to prevent caching."
  [obj]
  (loop [v obj
         throw? false]
    (if (instance? NonCached v)
      (recur (:v v) true)
      (if throw? (throw (ex-info "" {::non-cached v})) v))))

(defn key->ckey "Transform key into cache key" [key-fn k]
  (key-fn (or k '())))

(defn ^CacheBuilder spec->builder
  "Creates and configures common parameters on the builder."
  [{:memento.core/keys [concurrency initial-capacity size< weight< ttl fade refresh stats
                        kv-weight weak-keys weak-values soft-values ticker removal-listener
                        key-fn]}]
  (cond-> (CacheBuilder/newBuilder)
    concurrency (.concurrencyLevel concurrency)
    initial-capacity (.initialCapacity initial-capacity)
    weight< (.maximumWeight weight<)
    size< (.maximumSize size<)
    kv-weight (.weigher (reify Weigher (weigh [_this k v]
                                         (kv-weight k (cval->val v)))))
    weak-keys (.weakKeys)
    weak-values (.weakValues)
    soft-values (.softValues)
    ttl (.expireAfterWrite (parse-time-scalar ttl) (parse-time-unit ttl))
    fade (.expireAfterAccess (parse-time-scalar fade) (parse-time-unit fade))
    refresh (.refreshAfterWrite (parse-time-scalar refresh) (parse-time-unit refresh))
    ticker (.ticker (proxy [Ticker] [] (read [] (ticker))))
    removal-listener (.removalListener (reify RemovalListener
                                         (onRemoval [this n]
                                           (removal-listener
                                             (.getKey n)
                                             (cval->val (.getValue n))
                                             (.getCause n)))))
    stats (.recordStats)))

(defn cget [^Cache guava-cache {:memento.core/keys [key-fn ret-fn]} f args]
  (try
    (.get guava-cache (key->ckey key-fn args)
          (fn cache-load []
            (->> args (apply f) ret-fn val->cval process-non-cached)))
    (catch ExecutionException e (throw (.getCause e)))
    (catch UncheckedExecutionException e
      (let [cause (.getCause e)
            data (ex-data cause)]
        (if (contains? data ::non-cached)
          (::non-cached data)
          (throw cause))))))

(defrecord GCache [^Cache guava-cache spec f]
  b/Cache
  (get-cached [this args]
    (cget guava-cache spec f args))
  (invalidate [this args]
    (.invalidate guava-cache (key->ckey (:memento.core/key-fn spec) args))
    this)
  (invalidate-all [this] (.invalidateAll guava-cache) this)
  (put-all [this args-to-vals]
    (let [{:memento.core/keys [key-fn]} spec]
      (reduce-kv (fn guava-put-key [^Cache c k v]
                   (.put c (key->ckey key-fn k) (val->cval v)))
                 guava-cache
                 args-to-vals)
      this))
  (as-map [this]
    (persistent!
      (reduce (fn [m e] (assoc! m (key e) (cval->val (val e))))
              (transient {})
              (.asMap guava-cache)))))

(defn ^Cache region-cache [region-id] (b/region-cache region-id))

; spec requires:
; :key-fn and :ret-fn
(defrecord GRegionCache
  [region-id spec f]
  b/Cache
  (get-cached [this args]
    (if-let [cache (region-cache region-id)]
      (cget cache spec f args)
      (apply f args)))
  (invalidate [this args]
    (when-some [cache (region-cache region-id)]
      (.invalidate cache (key->ckey (:memento.core/key-fn spec) args)))
    this)
  (invalidate-all [this]
    (when-some [cache (region-cache region-id)]
      (doseq [e (.asMap cache)]
        (let [k (key e)]
          (when (= f (first k)) (.invalidate cache k)))))
    this)
  (put-all [this args-to-vals]
    (when-some [cache (region-cache region-id)]
      (let [{:memento.core/keys [key-fn]} spec]
        (reduce-kv (fn guava-put-key [^Cache c k v]
                     (.put c (key->ckey key-fn k) (val->cval v)))
                   cache
                   args-to-vals)))
    this)
  (as-map [this]
    (if-some [^Cache cache (b/region-cache region-id)]
      (persistent!
        (reduce (fn [m e]
                  (let [k (key e)]
                    (if (= f (first k))
                      (assoc! m (second k) (cval->val (val e)))
                      m)))
                (.asMap cache)))
      {})))

(defn spec->gregioncache [spec f]
  (->GRegionCache
    (:memento.core/region spec)
    (-> spec
        (select-keys [:memento.core/key-fn :memento.core/ret-fn])
        (update :memento.core/key-fn #(let [key-fn (or % identity)]
                                        (fn [args] [f (key->ckey key-fn args)])))
        (update :memento.core/ret-fn #(or % identity)))
    f))

(defn spec->gcache [spec f]
  (->GCache (.build (spec->builder spec))
            (merge #:memento.core {:key-fn identity :ret-fn identity} spec)
            f))

(defmethod b/create-cache :guava
  [spec f]
  (let [{:memento.core/keys [seed region]} spec]
    (cond->
      (if region
        (spec->gregioncache spec f)
        (spec->gcache spec f))
      (map? seed) (b/put-all seed))))

(defrecord GRegion [spec ^Cache cache]
  b/CacheRegion
  (started-region [this]
    (if (nil? cache)
      (assoc this :cache (.build (spec->builder spec)))
      this))
  (invalidate-region [this] (.invalidateAll cache))
  (region-spec [this] spec)
  (region-id [this] (:memento.core/region spec)))

(defmethod b/create-region :guava
  [spec]
  (->GRegion
    (with-meta (dissoc spec :memento.core/seed) (meta spec))
    nil))