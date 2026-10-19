(ns neorepl.sample-sleepy
  "A namespace that takes a while to load, for reloads to give up on, which
  the test runner leaves alone.")

(Thread/sleep 3000)
