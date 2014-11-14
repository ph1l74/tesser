(ns tesser.core-test
  (:require [clojure.test :refer :all]
            [clojure.math.numeric-tower :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check [clojure-test :refer :all]
                                [generators :as gen]
                                [properties :as prop]]
            [multiset.core :refer [multiset]]
            [tesser.utils :refer :all]
            [tesser.core :as t]))

(def test-count 1e2)

(deftest map-sum-test
  (is (= (->> (t/map inc)
              (t/sum)
              (t/tesser [[1 2 3] [4 5 6] []]))
         27)))

;(deftest facet-mean-test
;  (is (= (->> (t/facet)
;              (t/mean)
;              (t/tesser [[{:x 1, :y 2} {}]
;                        [{:y 3, :z 4}]]))
;         {:x 1, :y 2, :z 4})))

;; Utility functions
(defn approx=
  "Equal to within err fraction, or if one is zero, to within err absolute."
  [err x y]
  (if (or (zero? x) (zero? y))
    (< (- err) (- x y) err)
    (< (- 1 err) (/ x y) (+ 1 err))))

(def =ish
  "Almost equal"
  (partial approx= 1/1000))

(defn chunks
  "Given a generator for inputs, returns a generator that builds
  sequences of sequences of inputs."
  [input-gen]
  (gen/vector (gen/vector input-gen) 0 5))

(defn flatten1
  "Flattens a single level."
  [seq-of-seqs]
  (apply concat seq-of-seqs))

;; Tests

(defspec map-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (->> (t/map inc)
                            (t/map (partial * 2))
                            (t/into (multiset))
                            (t/tesser chunks))
                       (->> chunks
                            flatten1
                            (map inc)
                            (map (partial * 2))
                            (into (multiset)))))))

(defspec mapcat-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (->> (t/mapcat range)
                            (t/filter even?)
                            (t/into (multiset))
                            (t/tesser chunks))
                       (->> chunks
                            flatten1
                            (mapcat range)
                            (filter even?)
                            (into (multiset)))))))

(defspec keep-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (->> (t/keep #(when (even? %) (inc %)))
                            (t/into (multiset))
                            (t/tesser chunks))
                       (->> chunks
                            flatten1
                            (keep #(when (even? %) (inc %)))
                            (into (multiset)))))))

(defspec filter-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (->> (t/filter odd?)
                            (t/into (multiset))
                            (t/tesser chunks))
                       (->> chunks
                            flatten1
                            (filter odd?)
                            (into (multiset)))))))

(defspec remove-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (->> (t/remove odd?)
                            (t/into (multiset))
                            (t/tesser chunks))
                       (->> chunks
                            flatten1
                            (remove odd?)
                            (into (multiset)))))))

(defspec into-vec-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (sort (t/tesser chunks (t/into [])))
                       (sort (flatten1 chunks))))))

(defspec into-set-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (t/tesser chunks (t/into #{}))
                       (set (flatten1 chunks))))))

(defspec take-spec
  test-count
  ; Our chunks will be random partitionings of the integers, and we'll take
  ; them into a vector, then verify it contains n unique elements.
  (prop/for-all [n     gen/pos-int
                 sizes (gen/vector gen/pos-int 0 10)]
;                (prn) (prn)
                (let [total  (reduce + sizes)
                      chunks (->> sizes
                                  (reduce (fn [[start chunks] size]
                                            [(+ start size)
                                             (->> start
                                                  (iterate inc)
                                                  (take size)
                                                  (conj chunks))])
                                          [0 []])
                                  second)]
;                  (prn :n n :chunks chunks)
                  (let [x (->> (t/take n)
                               (t/into [])
                               (t/tesser chunks))]
;                    (prn :total total :n n :result x)
                    (is (and (or (= (count x) n)         ; Exactly n inputs
                                 (and (< (count x) n) ; Not enough to hit n
                                      (= (count x) total)))
                             ; Unique
                             (= (count x)
                                (count (set x)))))))))

(defspec take-take-spec
  test-count
  ; Our chunks will be random partitionings of the integers, and we'll take
  ; them into a vector, then verify it contains n unique elements. Performing
  ; two takes verifies that the reduced optimizations compose well. :)
  (prop/for-all [n     gen/pos-int
                 sizes (gen/vector gen/pos-int 0 10)]
;                (prn) (prn)
                (let [total  (reduce + sizes)
                      chunks (->> sizes
                                  (reduce (fn [[start chunks] size]
                                            [(+ start size)
                                             (->> start
                                                  (iterate inc)
                                                  (take size)
                                                  (conj chunks))])
                                          [0 []])
                                  second)]
;                  (prn :n n :chunks chunks)
                  (let [x (->> (t/take (inc n))
                               (t/take n)
                               (t/into [])
                               (t/tesser chunks))]
;                    (prn :total total :n n :result x)
                    (is (and (or (= (count x) n)         ; Exactly n inputs
                                 (and (< (count x) n) ; Not enough to hit n
                                      (= (count x) total)))
                             ; Unique
                             (= (count x)
                                (count (set x)))))))))

(defspec post-combine-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (->> (t/map inc)
                            (t/map str)
                            (t/into #{})
                            (t/post-combine sort)
                            (t/post-combine (partial map read-string))
                            (t/tesser chunks))
                       (->> (flatten1 chunks)
                            (map inc)
                            (map str)
                            (into #{})
                            sort
                            (map read-string))))))

;; Splitting folds

(defspec facet-spec
  test-count
  ; Sum over maps of keywords to ints
  (prop/for-all [chunks (chunks
                          (gen/resize 4 (gen/map (gen/elements [:a :b :c :d :e])
                                                 gen/int)))]
                (is (= (->> (t/facet)
                            (t/sum)
                            (t/tesser chunks))
                       (->> chunks
                            flatten1
                            (apply merge-with + {}))))))

(defspec fuse-spec
  test-count
  ; sum, set, and multiset over ints
  (prop/for-all [chunks (chunks gen/int)]
                (let [inputs (flatten1 chunks)]
                  (is (= (->> (t/fuse {:sum      (t/sum)
                                       :set      (t/into #{})
                                       :multiset (t/into (multiset))})
                              (t/tesser chunks))
                         {:sum      (reduce + inputs)
                          :set      (set inputs)
                          :multiset (into (multiset) inputs)})))))

;; Basic reductions

(defspec count-spec
  test-count
  (prop/for-all [chunks (chunks gen/simple-type)]
                (is (= (t/tesser chunks (t/count))
                       (count (flatten1 chunks))))))

(defspec set-spec
  test-count
  (prop/for-all [chunks (chunks gen/simple-type)]
                (is (= (t/tesser chunks (t/set))
                       (set (flatten1 chunks))))))

(defspec frequencies-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (t/tesser chunks (t/frequencies))
                       (frequencies (flatten1 chunks))))))

(defspec some-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (let [e          (t/tesser chunks (t/some even?))
                      candidates (set (filter even? (flatten1 chunks)))]
                  (is (or (contains? candidates e)
                          (and (empty? candidates) (nil? e)))))))

(defspec any-spec
  test-count
  (prop/for-all [chunks (chunks gen/simple-type)]
                (let [e           (t/tesser chunks (t/any))
                      candidates  (set (flatten1 chunks))]
                  (is (or (contains? candidates e)
                          (and (empty? candidates) (nil? e)))))))

;; Comparable folds

(defspec max-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (let [m (t/tesser chunks (t/max))]
                  (if (every? empty? chunks)
                    (nil? m)
                    (= m (reduce max (flatten1 chunks)))))))

(defspec min-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (let [m (t/tesser chunks (t/min))]
                  (if (every? empty? chunks)
                    (nil? m)
                    (= m (reduce min (flatten1 chunks)))))))

(defspec range-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (let [inputs (flatten1 chunks)]
                  (= (t/tesser chunks (t/range))
                     (if (every? empty? chunks)
                       [nil nil]
                       [(reduce min inputs) (reduce max inputs)])))))


;; Numeric folds

(defspec sum-spec
  test-count
  (prop/for-all [chunks (chunks gen/int)]
                (is (= (t/tesser chunks (t/sum))
                        (reduce + 0 (flatten1 chunks))))))

(defn mean
  [coll]
  (assert (not (empty? coll)))
  (/ (reduce + coll) (count coll)))

(defspec mean-spec
  test-count
  (prop/for-all [chunks (gen/such-that (partial some not-empty)
                                       (chunks gen/int))]
                (is (== (t/tesser chunks (t/mean))
                        (mean (flatten1 chunks))))))


(defn variance
  [coll]
  (/ (->> coll
          (map #(expt (- % (mean coll)) 2))
          (reduce +))
     (max (dec (count coll)) 1)))

(defspec variance-spec
  test-count
  (prop/for-all [chunks (gen/such-that (partial some not-empty)
                                       (chunks gen/int))]
                (=ish (t/tesser chunks (t/variance))
                      (variance (flatten1 chunks)))))

(defspec standard-deviation-spec
  test-count
  (prop/for-all [chunks (gen/such-that (partial some not-empty)
                                       (chunks gen/int))]
                (=ish (t/tesser chunks (t/standard-deviation))
                      (sqrt (variance (flatten1 chunks))))))

(defn covariance
  [fx fy coll]
  (let [coll (filter fx (filter fy coll))]
    (if (empty? coll)
      nil
      (let [mean-x (mean (map fx coll))
            mean-y (mean (map fy coll))]
        (double (/ (reduce + (map #(* (- (fx %) mean-x)
                                      (- (fy %) mean-y))
                                  coll))
                   (count coll)))))))

(defspec covariance-spec
  test-count
  ; Take maps like {}, {:x 1}, {:x 2 :y 3} and compute covariance
  (prop/for-all [chunks (chunks (gen/map (gen/elements [:x :y]) gen/int))]
                (is (= (->> (t/covariance :x :y)
                            (t/tesser chunks))
                       (covariance :x :y (flatten1 chunks))))))

(defspec covariance-matrix-spec
  test-count
  (prop/for-all [chunks (chunks (gen/map (gen/elements [:x :y :z]) gen/int))]
                (let [inputs (flatten1 chunks)]
                  (is (= (->> (t/covariance-matrix {"x" :x "y" :y "z" :z})
                              (t/tesser chunks))
                         ; NOTE: depends on math.combinatorics order; fragile
                         ; but easy to fix.
                         (let [xy (covariance :x :y inputs)
                               xz (covariance :x :z inputs)
                               yz (covariance :y :z inputs)]
                           {["x" "y"] xy
                            ["x" "z"] xz
                            ["y" "x"] xy
                            ["y" "z"] yz
                            ["z" "x"] xz
                            ["z" "y"] yz}))))))

(defn correlation
  [fx fy coll]
  "http://mathworld.wolfram.com/CorrelationCoefficient.html"
  (let [coll (filter fx (filter fy coll))]
    (when-not (empty? coll)
      (let [xs (map fx coll)
            ys (map fy coll)
            mx (mean (map fx coll))
            my (mean (map fy coll))
            mxs (map #(- % mx) xs)
            mys (map #(- % my) ys)]
        (try
          (/ (reduce + (map * mxs mys))
             (sqrt (* (reduce + (map * mxs mxs))
                      (reduce + (map * mys mys)))))
          (catch ArithmeticException e
            nil))))))

(defspec correlation-spec
  test-count
  (prop/for-all [chunks (chunks (gen/map (gen/elements [:x :y]) gen/int))]
                (is (= (->> (t/correlation :x :y)
                            (t/tesser chunks))
                       (correlation :x :y (flatten1 chunks))))))

(defspec correlation-matrix-spec
  test-count
  (prop/for-all [chunks (chunks (gen/map (gen/elements [:x :y :z]) gen/int))]
                (let [inputs (flatten1 chunks)]
                  (is (= (->> (t/correlation-matrix {"x" :x "y" :y "z" :z})
                              (t/tesser chunks))
                         ; NOTE: depends on math.combinatorics order; fragile
                         ; but easy to fix.
                         (let [xy (correlation :x :y inputs)
                               xz (correlation :x :z inputs)
                               yz (correlation :y :z inputs)]
                           {["x" "y"] xy
                            ["x" "z"] xz
                            ["y" "x"] xy
                            ["y" "z"] yz
                            ["z" "x"] xz
                            ["z" "y"] yz}))))))