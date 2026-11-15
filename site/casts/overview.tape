# The hero: connecting, the line editor, and a value or two.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.8

# Enter in an unfinished form starts a line indented for it.
Type "(defn greet"
Enter
Type "\"Says hello.\""
Enter
Type "[who]"
Enter
Type "(str \"Hello, \" who \"!\"))"
Sleep 1.2
Enter
Wait /#'user\/greet/
Sleep 1.2

# TAB completes, and the arglists show under the input.
Type "(gre"
Sleep 0.4
Tab
Sleep 0.6
Type " \"Clojure\""
Sleep 1.2
Type ")"
Enter
Wait /Hello, Clojure!/
Sleep 1.5

Type "(require 'demo.core)"
Enter
Wait /nil/
Sleep 0.6
Type "demo.core/orders"
Enter
Wait /Rich/
Sleep 3
