(ns wumpus.dodecahedron)

(defn make-cave-map []
  (let [edges [[1 2] [2 3] [3 4] [4 5] [5 1]           ; top pentagon
               [1 6] [2 7] [3 8] [4 9] [5 10]          ; top to upper ring
               [6 11] [6 15] [7 11] [7 12] [8 12]      ; upper to lower ring
               [8 13] [9 13] [9 14] [10 14] [10 15]
               [11 16] [12 17] [13 18] [14 19] [15 20]  ; lower ring to bottom
               [16 17] [17 18] [18 19] [19 20] [20 16]] ; bottom pentagon
        empty-map (into {} (map (fn [r] [r #{}]) (range 1 21)))]
    (reduce (fn [m [a b]]
              (-> m
                  (update a conj b)
                  (update b conj a)))
            empty-map edges)))
