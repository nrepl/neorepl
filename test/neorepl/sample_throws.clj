(ns neorepl.sample-throws
  "A namespace that doesn't load, which the test runner leaves alone.")

(throw (ex-info "Doesn't load." {}))
