(ns demo.core
  "A small coffee shop, for neorepl's recordings."
  (:require [clojure.string :as str]))

(def menu
  "What's on offer, with prices in cents."
  {:espresso {:price 250 :size :small :tags #{:hot :strong}}
   :latte {:price 380 :size :large :tags #{:hot :milk}}
   :cold-brew {:price 420 :size :large :tags #{:cold :strong}}})

(def orders
  "Today's orders so far."
  [{:id 1 :customer "Ada" :paid? true
    :items [{:drink :latte :qty 2}]}
   {:id 2 :customer "Rich" :paid? false
    :items [{:drink :espresso :qty 1} {:drink :cold-brew :qty 1}]}])

(defn price
  "The price of qty drinks, in cents."
  [drink qty]
  (* qty (get-in menu [drink :price])))

(defn total
  "What an order comes to, in cents."
  [{:keys [items]}]
  (reduce + (map (fn [{:keys [drink qty]}] (price drink qty)) items)))

(defn format-cents
  "Cents as dollars, like $3.80."
  [cents]
  (format "$%d.%02d" (quot cents 100) (rem cents 10)))

(defn find-order
  "The order with id, or an error saying there's none."
  [id]
  (or (first (filter #(= id (:id %)) orders))
      (throw (ex-info (str "No order " id) {:id id :known (mapv :id orders)}))))

(defn receipt
  "An order's receipt, a line for each item and one for the total."
  [order]
  (str/join "\n" (concat (for [{:keys [drink qty]} (:items order)]
                           (str qty " x " (name drink) " " (format-cents (price drink qty))))
                         [(str "total " (format-cents (total order)))])))
