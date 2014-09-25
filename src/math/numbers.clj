(ns math.numbers
  (:require [math.generic :as g]
            [math.numsymb :as ns]))

;; still to be done: constant folding across expressions

(extend-protocol g/Value
  Long
  (id+? [x] (zero? x))
  (id*? [x] (= x 1))
  (zero-like [x] 0)
  Double
  (id+? [x] (zero? x))
  (id*? [x] (= x 1.0))
  (zero-like [x] 0.0))

(defn- make-numerical-combination
  ([operator] (make-numerical-combination operator identity))
  ([operator transform-operands]
     (fn [& operands]
       (ns/make-numsymb-expression operator (transform-operands operands)))))

(defn- make-binary-operation [key operation commutative?]
  (g/defhandler key [number? number?] operation)
  (g/defhandler key [g/abstract-number? g/abstract-number?] (make-numerical-combination key))
  (g/defhandler key [number? g/abstract-number?] (make-numerical-combination key))
  (g/defhandler key [g/abstract-number? number?]
    (make-numerical-combination key (if commutative? reverse identity))))

(make-binary-operation :+ + true)
(make-binary-operation :* * true)
(make-binary-operation :- - false)
(make-binary-operation :/ / false)

(g/defhandler :neg [g/abstract-number?] (make-numerical-combination :negate))
(g/defhandler :neg [number?] -)
(g/defhandler :inv [number?] /)

(println "numbers initialized")