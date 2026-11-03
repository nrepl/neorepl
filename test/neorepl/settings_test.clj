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
       (testing "what's no setting, or set to what it doesn't take, said and left out"
         (is (= {:settings {:taps false}
                 :problems [(str file " sets :eldok, which is no setting")
                            (str file " sets :quiet to \"yes\", which takes true or false")]}
                (read "{:eldok false :quiet \"yes\" :taps false}"))))
       (testing "a file that's no map of settings"
         (is (= {:settings {} :problems [(str file " should have a map of settings, like {:eldoc false}")]}
                (read "[:eldoc false]")))
         (let [{:keys [settings problems]} (read "{:eldoc")]
           (is (= {} settings))
           (is (re-find #"^Can't read .*config\.edn: " (first problems)))))))))

(deftest parse-value-test
  (are [text value] (= value (settings/parse-value text))
    "on" true
    "ON" true
    "true" true
    "off" false
    "false" false
    "maybe" nil))
