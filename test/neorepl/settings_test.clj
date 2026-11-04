(ns neorepl.settings-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [are deftest is testing]]
            [neorepl.settings :as settings]
            [neorepl.test-server :refer [call-with-temp-dir spit-file]]))

(deftest read-file-test
  (call-with-temp-dir
   (fn [dir]
     (let [file (io/file dir "config.edn")
           read #(settings/read-file (spit-file dir "config.edn" %))]
       (is (= {:settings {:eldoc false :quiet true} :problems []}
              (read "{:eldoc false :quiet true}")))
       (testing "nothing without a file, or with nothing in it"
         (are [read] (= {:settings {} :problems []} read)
           (settings/read-file nil)
           (settings/read-file (io/file dir "none.edn"))
           (read "")
           (read ";; Nothing yet")))
       (testing "limits, a number or false"
         (is (= {:settings {:print-length 50 :print-level false} :problems []}
                (read "{:print-length 50 :print-level false}"))))
       (testing "what's no setting, or set to what it doesn't take, said and left out"
         (is (= {:settings {:taps false}
                 :problems [(str file " sets :eldok, which is no setting")
                            (str file " sets :quiet to \"yes\", which takes true or false")]}
                (read "{:eldok false :quiet \"yes\" :taps false}")))
         (is (= {:settings {}
                 :problems [(str file " sets :print-length to 0, which takes a number, or false")
                            (str file " sets :print-level to true, which takes a number, or false")
                            (str file " sets :pretty to 1, which takes true or false")]}
                (read "{:print-length 0 :print-level true :pretty 1}"))))
       (testing "a file that's no map of settings"
         (is (= {:settings {} :problems [(str file " should have a map of settings, like {:eldoc false}")]}
                (read "[:eldoc false]")))
         (let [{:keys [settings problems]} (read "{:eldoc")]
           (is (= {} settings))
           (is (re-find #"^Can't read .*config\.edn: " (first problems)))))))))

(deftest parse-value-test
  (are [k text value] (= value (settings/parse-value k text))
    :eldoc "on" true
    :eldoc "ON" true
    :eldoc "true" true
    :eldoc "off" false
    :eldoc "false" false
    :eldoc "maybe" nil
    :eldoc "3" nil
    :print-length "3" 3
    :print-length "120" 120
    :print-length "off" false
    :print-length "on" nil
    :print-length "0" nil
    :print-length "-1" nil
    :print-length "99999999999999999999" nil))

(deftest toggles-test
  (testing "the settings on or off, but for autoreload"
    (is (= [:color :eldoc :pretty :quiet :server-output :taps] (map :name settings/toggles)))))
