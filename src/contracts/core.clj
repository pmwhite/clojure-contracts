(ns contracts.core
  (:refer-clojure :exclude [and or not vector-of])
  (:require [clojure.core :as clj])
  (:use [clojure.core.match :only [match]]
        [clojure.walk :only [postwalk]]))

(declare =>)
(def current-var (atom nil))

(defn humanize-symbol-name [s]
  (condp #(.startsWith %2 %1) s
    "%&" "<rest-args>"
    "%" (format "<%s arg>" (case s
                             ("%" "%1") "first"
                             "%2" "second"
                             "%3" "third"
                             (str (subs s 1) "th")))
    "(clojure.core/deref (var " (subs s 25 (- (count s) 2))
    "(clojure.core/deref " (str "@" (subs s 20 (dec (count s))))
    s))

(defn humanize-pred-expr [pred-expr checked-expr]
  (match pred-expr ([(:or fn fn*) [arg] body] :seq)
    (-> pred-expr
        (nth 2)
        (->> (postwalk #(if (= % arg) checked-expr %))))))

(defn report [{:keys [type var pred expr value]}]
  (let [humanized-expr (if (= type :post)
                         "<result>"
                         (humanize-symbol-name (pr-str expr)))
        expecting (if-let [humanized-pred (humanize-pred-expr pred (symbol humanized-expr))]
                    (pr-str humanized-pred)
                    (format "%s is: %s" humanized-expr pred))]
    (format "%s failed for %s %n Expecting: %s %n Given: %s"
            (case type
              :pre "Precondition"
              :post "Postcondition"
              :invariant "Invariant")
            (clj/or var "<undefined>")
            expecting
            (pr-str value))))

(defn fn-contract-expr? [expr]
  (clj/and (seq? expr)
           (symbol? (first expr))
           (= (resolve (first expr)) #'=>)))

;; TODO: rename
(defn gen-check* [{:keys [type cond return-val pred expr value]}]
  `(if ~cond
     ~return-val
     (throw (AssertionError.
             (report {:value ~value
                      :type ~type
                      :pred '~pred
                      :expr '~expr
                      :var ~(deref current-var)})))))

(defn gen-check [type exprs+preds]
  (->> (for [[expr pred] exprs+preds
             :let [[cond ret] (if (fn-contract-expr? pred)
                                [`(fn? ~expr) `(~pred ~expr)]
                                [`(~pred ~expr) expr])]]
         `['~expr ~(gen-check* {:cond cond
                                :return-val ret
                                :expr expr
                                :value expr
                                :type type
                                :pred pred})])
       (into {})))

(defn wrap-in-list-if [pred x]
  (if (pred x)
    (list x)
    x))

(defn amp? [x]
  (= x '&))

(defn split-at-amp
  "Returns [things-before-& things-after-&]"
  [coll]
  (let [[normal maybe-rest] (split-with (complement amp?) coll)]
    [normal (when (amp? (first maybe-rest))
              (next maybe-rest))]))

(defn gen-constrained-body [f post pre args]
  (let [[pre-check-results result] (map gensym ["pre-check-results" "result"])
        [normal-args rest-args] (split-at-amp args)]
    `([~@args]
        (let [~pre-check-results ~(gen-check :pre pre)
              ;; contract can alter the values of args, so we rebind them
              ~@(mapcat (fn [arg] [arg `(get ~pre-check-results '~arg ~arg)])
                        (concat normal-args rest-args))
              ~result (apply ~f ~@normal-args ~(clj/or (first rest-args) []))]
          ~(-> (gen-check :post {result post}) first val)))))


(defn normalize-pre
  "Returns preconditions in the form ([pre ...] ...)"
  [expr]
  (cond
   (clj/and (list? expr) (every? vector? expr)) expr
   (vector? expr) (list expr)
   :else (list [expr])))

(defn build-numbered-args
  "Given a coll of preconditions, returns a vector of symbols prefixed
  by % (as those used in clojure's #(...))."
  [pre]
  (let [[normal-pre rest-pre] (split-at-amp pre)
        normal-args (map (fn [i] (symbol (str "%" i)))
                         (range 1 (inc (count normal-pre))))]
    (vec (cond
          (= 1 (count pre)) [(symbol "%")]
          rest-pre (concat normal-args (map symbol ["&" "%&"])) ; %& becomes rest__17578# if quote it directly
          :else normal-args))))

(defn build-pre-map [args pre]
  (zipmap (remove amp? args)
          (remove amp? pre)))

(defmacro =>
  ([pre post]
     (let [pre-list (normalize-pre pre)
           args-list (map build-numbered-args pre-list)
           pre-map (map build-pre-map args-list pre-list)]
       `(=> ~args-list ~pre-map ~post)))
  ([args pre post]
     (let [arglist (wrap-in-list-if vector? args)
           pre (wrap-in-list-if map? pre)
           f (gensym "f")]
       `(fn [~f]
          (fn ~@(map (partial gen-constrained-body f post) pre arglist))))))

(defn normalize-contract [expr]
  (match expr
    [pre '=> post] (list* `=> (map normalize-contract [pre post]))
    :else expr))

(defn gen-iref-contract [target pred]
  (let [newval (gensym "newval")]
    `(fn [~newval]
       ~(gen-check* {:type :invariant
                     :cond `(~pred ~newval)
                     :return-val true
                     :pred pred
                     :expr `(deref ~target)
                     :value newval}))))

(defmacro provide-contract [target contract]
  (let [contract (normalize-contract contract)
        target-var (if (symbol? target)
                     (resolve target)
                     target)]
    (reset! current-var target-var)
    `(do ~(cond
           (fn-contract-expr? contract)
           `(alter-var-root (var ~target) ~contract)
           :else
           `(set-validator! ~target ~(gen-iref-contract target contract)))
         (reset! current-var nil))))

(defmacro provide-contracts [& clauses]
  (cons `do
        (for [clause clauses]
          `(provide-contract ~@clause))))


(load "preds")
(load "curried") ; this line should be commented out during development
