(ns neorepl.sample-slow-load
  "A namespace that takes a while to load, for test runs to time out on,
  which the test runner leaves alone.")

(Thread/sleep 3000)
