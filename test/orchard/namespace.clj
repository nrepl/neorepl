(ns orchard.namespace
  "Stands in for cider-nrepl's orchard, which neorepl's whole-project test
  runs ask for the project's namespaces.")

(def project
  "The namespaces project-namespaces says the project has."
  (atom []))

(def missing?
  "Whether to fail as a server without orchard would."
  (atom false))

(defn project-namespaces []
  (when @missing?
    (throw (java.io.FileNotFoundException. "Could not locate orchard/namespace__init.class")))
  @project)
