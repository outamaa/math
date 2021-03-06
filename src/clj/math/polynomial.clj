;; Copyright (C) 2015 Colin Smith.
;; This work is based on the Scmutils system of MIT/GNU Scheme.
;;
;; This is free software;  you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation; either version 3 of the License, or (at
;; your option) any later version.

;; This software is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; General Public License for more details.

;; You should have received a copy of the GNU General Public License
;; along with this code; if not, see <http://www.gnu.org/licenses/>.

(ns math.polynomial
  (:import (clojure.lang PersistentTreeMap))
  (:require [clojure.set :as set]
            [math.value :as v]
            [math.euclid :refer :all]
            [math.generic :as g]
            [math.numsymb :as sym]
            [math.expression :as x]))

(declare operator-table operators-known)

(defrecord Polynomial [^long arity ^PersistentTreeMap xs->c]
  v/Value
  (nullity? [p] (empty? (:xs->c p)))
  (numerical? [_] false)
  (unity? [p] (and (= (count (:xs->c p)) 1)
                 (let [[exponents coef] (first (:xs->c p))]
                   (and (every? zero? exponents)
                        (g/one? coef))))))

(defn- base? [p] (not (instance? Polynomial p)))

(defn make
  "When called with two arguments, the first is the arity
  (number of indeterminates) of the polynomial followed by a sequence
  of exponent-coefficient pairs. Each exponent should be a vector with
  length equal to the arity, with integer exponent values.

  When called with one argument, the sequence is interpreted as a
  dense sequence of coefficients of an arity-1 (univariate)
  polynomial. The coefficients begin with the constant term and
  proceed to each higher power of the indeterminate. For example, x^2
  - 1 can be constructed by (make -1 0 1)."
  ([a xc-pairs]
   (->> xc-pairs
        (filter (fn [[_ c]] (not (g/zero? c))))
        (into (sorted-map))
        (Polynomial. a)))
  ([coefficients]
   (make 1 (zipmap (map vector (iterate inc 0)) coefficients))))

(defn degree
  [p]
  (if (g/zero? p) -1
      (reduce max (map #(reduce + 0 %) (keys (:xs->c p))))))

(defn- check-same-arity [p q]
  (let [ap (:arity p)
        aq (:arity q)]
    (cond (= ap aq) ap
          :else (throw (ArithmeticException. "mismatched polynomial arity")))))

(defn- poly-map
  "Map the function f over the coefficients of p, returning a new Polynomial."
  [f {:keys [arity xs->c]}]
  (make arity (for [[xs c] xs->c] [xs (f c)])))

(defn- poly-merge
  "Merge the polynomials together, combining corresponding coefficients with f.
  The result is not a polynomial object, but rather a sequence
  of [exponent, coefficient] pairs, suitable for further processing or
  canonicalization. Merged monomials with zero coefficient are
  dropped."
  [f p q]
  (loop [P (:xs->c p)
         Q (:xs->c q)
         R (sorted-map)]
    (cond
      (empty? P) (into R (for [[xs c] Q
                               :let [c1 (f 0 c)]
                               :when (not (g/zero? c1))] [xs c1]))
      (empty? Q) (into R (for [[xs c] P
                               :let [c1 (f c 0)]
                               :when (not (g/zero? c1))] [xs c1]))
      :else (let [[xp cp] (first P)
                  [xq cq] (first Q)
                  order (compare xp xq)]
              (cond
                (zero? order) (let [v (f cp cq)]
                                     (recur (rest P) (rest Q)
                                            (if (not (g/zero? v))
                                              (assoc R xp v)
                                              R)))
                (< order 0) (recur (rest P) Q (assoc R xp (f cp 0)))
                :else (recur P (rest Q) (assoc R xq (f 0 cq))))))))

(defn new-variables
  "Creates a sequence of identity (i.e., x) polynomials, one for each
  of arity indeterminates."
  [arity]
  (for [a (range arity)]
    (make arity [[(vec (map #(if (= % a) 1 0) (range arity))) 1]])))

(def negate (partial poly-map g/negate))

(defn- make-constant
  "Return a constant polynomial of the given arity."
  [arity c]
  (make arity [[(vec (repeat arity 0)) c]])
  )
(defn- add-constant
  "Adds the constant c to poly. The result is normalized."
  [poly c]
  (let [{:keys [arity xs->c]} poly]
    (make arity (update-in xs->c [(vec (repeat arity 0))] #(g/+ (or % 0) c)))))

(defn add
  "Adds the polynomials p and q (either or both of which might just be
  constants in the base ring)."
  [p q]
  (let [p-base? (base? p)
        q-base? (base? q)]
    (cond (and p-base? q-base?) (g/+ p q)
          p-base? (add-constant q p)
          q-base? (add-constant p q)
          (g/zero? p) q
          (g/zero? q) p
          :else (let [a (check-same-arity p q)
                      sum (poly-merge g/+ p q)]
                  (make a sum)))))

(defn- add-denormal
  "Add-denormal adds the (order, coefficient) pair to the polynomial p,
  expecting that p is currently in sparse form (i.e., not a primitive number)
  and without normalizing the result (e.g., to see if the polynomial has
  become constant or a term has dropped out). Useful in intermediate steps
  of polynomial computations."
  [ocs [exponents coefficient]]
  (assoc ocs exponents (g/+ (get ocs exponents 0) coefficient)))

(defn sub
  "Subtract the polynomial q from the polynomial p."
  [p q]
  (let [p-base? (base? p)
        q-base? (base? q)]
    (cond (and p-base? q-base?) (g/- p q)
          p-base? (add-constant (negate q) p)
          q-base? (add-constant p (- q))
          (g/zero? p) (negate q)
          (g/zero? q) p
          :else (let [a (check-same-arity p q)
                      diff (poly-merge g/- p q)]
                  (make a diff)))))

(defn mul
  "Multiply polynomials p and q, and return the product."
  [p q]
  (let [p-base? (base? p)
        q-base? (base? q)]
    (cond (and p-base? q-base?) (g/* p q)
          (g/zero? p) 0
          (g/zero? q) 0
          p-base? (poly-map #(g/* p %) q)
          q-base? (poly-map #(g/* % q) p)
          (g/one? p) q
          (g/one? q) p
          :else (let [a (check-same-arity p q)]
                  (make a (reduce add-denormal
                                  (sorted-map)
                                  (for [[xp cp] (:xs->c p)
                                        [xq cq] (:xs->c q)]
                                    [(vec (map + xp xq)) (g/* cp cq)])))))))

(defn content [p]
  (->> p :xs->c (map second) (reduce (comp first extended-euclid))))

(defn- lead-term
  "Return the leading (i.e., highest degree) term of the polynomial
  p. The return value is [exponents coefficient]."
  [p]
  (-> p :xs->c rseq first))


(defn divide
  "Divide polynomial u by v, and return the pair of [quotient, remainder]
  polynomials. This assumes that the coefficients are drawn from a field,
  and so support division."
  [u v]
  (let [u-base? (base? u)
        v-base? (base? v)]
    (cond (and u-base? v-base?) [(g/divide u v) 0]
          v-base? [(poly-map #(g/divide % v) u) 0]
          u-base? [0 v]
          :else (let [arity (check-same-arity u v)
                      [vn-exponents vn-coefficient] (lead-term v)]
                  (loop [quotient (make arity []) remainder u]
                    ;; find a term in the remainder into which the
                    ;; lead term of the divisor can be divided.
                    (let [good-terms (->> remainder
                                          :xs->c rseq
                                          (map (fn [[xs c]]
                                                 [(map - xs vn-exponents) c]))
                                          (filter (fn [[residues _]]
                                                    (and (not-empty residues)
                                                         (every? (complement neg?) residues)))))]
                      (if-let [[residues coefficient] (first good-terms)]
                        (let [new-coefficient (g/divide coefficient vn-coefficient)
                              new-term (make arity [[(vec residues) new-coefficient]])]
                          (recur (add quotient new-term)
                                 (sub remainder (mul new-term v))))
                        [quotient remainder])))))))

(defn expt
  "Raise the polynomial p to the (integer) power n."
  [p n]
  (cond (base? p) (g/expt p n)
        (or (not (integer? n))
            (< n 0)) (throw (ArithmeticException.
                             (str "can't raise poly to " n)))
        (g/one? p) p
        (g/zero? p) (if (zero? n)
                      (throw (ArithmeticException. "poly 0^0"))
                    p)
        (zero? n) (make-constant (:arity p) 1)
        :else (loop [x p c n a (make-constant (:arity p) 1)]
                (if (zero? c) a
                    (if (even? c)
                      (recur (mul x x) (quot c 2) a)
                      (recur x (dec c) (mul x a)))))))

(defn graded-lex-order
  "An ordering on monomials. X < Y if X has higher total degree than
  Y. In case of ties, X < Y if Y < X lexicographically.  This is
  intended, when used as the comparator in an ascending sort, to
  produce an ordering like: x^2 + xy + y^2 + x + y + 1, when the monomials
  are sorted in ascending order."
  [xs ys]
  (let [deg #(reduce + %)
        xd (deg xs)
        yd (deg ys)]
    (cond (> xd yd) -1
          (< xd yd) 1
          :else (compare ys xs))))

(defn expression->
  "Convert an expression into Flat Polynomial canonical form. The
  expression should be an unwrapped expression, i.e., not an instance
  of the Expression type, nor should subexpressions contain type
  information. This kind of simplification proceeds purely
  symbolically over the known Flat Polynomial operations; other
  operations outside the arithmetic available in polynomials over
  commutative rings should be factored out by an expression analyzer
  before we get here. The result is a Polynomial object representing the
  polynomial structure of the input over the unknowns."
  [expr cont]
  (let [expression-vars (sort (set/difference (x/variables-in expr) operators-known))
        new-bindings (zipmap expression-vars (new-variables (count expression-vars)))
        environment (into operator-table new-bindings)]
   (cont ((x/walk-expression environment) expr) expression-vars)))

(defn ->expression
  "This is the output stage of Flat Polynomial canonical form simplification.
  The input is a Polynomial object, and the output is an expression
  representing the evaluation of that polynomial over the
  indeterminates extracted from the expression at the start of this
  process."
  [^Polynomial p vars]
  (if (base? p) p
      (reduce
       sym/add 0
       (map (fn [[exponents coefficient]]
              (sym/mul coefficient
                       (reduce sym/mul 1 (map (fn [exponent var]
                                                (sym/expt var exponent))
                                              exponents vars))))
            (->> p :xs->c (sort-by first graded-lex-order))))))

;; The operator-table represents the operations that can be understood
;; from the point of view of a polynomial over a commutative ring. The
;; functions take polynomial inputs and return
;; polynomials.

(def ^:private operator-table
  {'+ #(reduce add %&)
   '- (fn [arg & args]
        (if (some? args) (sub arg (reduce add args)) (negate arg)))
   '* #(reduce mul %&)
   'negate negate
   'expt expt
   'square #(mul % %)
   'cube #(mul % (mul % %))
   ;`'g/gcd gcd
   })

(def operators-known (set (keys operator-table)))
