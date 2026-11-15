# Reloading what changed before each evaluation.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type ",ns demo.core"
Enter
Wait /demo.core=> $/
Sleep 0.5
Type ",autoreload"
Enter
Wait /before each evaluation/
Sleep 1
Type "(price :latte 2)"
Enter
Wait /760/
Sleep 1.5
# The price goes up, in an editor somewhere.
Shell "perl -pi -e 's/:price 380/:price 420/' src/demo/core.clj"
Sleep 0.5
Up
Sleep 0.8
Enter
Wait /840/
Sleep 1.5
# A new function, in an editor somewhere.
Shell "printf '\\n(defn tip\\n  \"A tenth on top, in cents.\"\\n  [cents]\\n  (quot cents 10))\\n' >> src/demo/core.clj"
Sleep 0.5
Type "(tip 840)"
Enter
Wait /^84$/
Sleep 1.5
Type ",autoreload off"
Enter
Wait /Not reloading/
Sleep 3
