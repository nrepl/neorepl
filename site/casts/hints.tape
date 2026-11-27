# What to try after an error: the fix for a typo, and ,debug for the rest.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type ",ns demo.core"
Enter
Wait /demo.core=> $/
Sleep 0.5
Type "(totl (find-order 1))"
Enter
Wait /,fix 1/
Sleep 2.5
Type ",fix 1"
Enter
Wait /760/
Sleep 1.5
# The ex-data, the line it threw at, and where to see more.
Type "(find-order 3)"
Enter
Wait /,debug stops at the throw/
Sleep 3.5
Type ",debug"
Enter
Wait /debug> $/
Sleep 2
Type "l"
Wait /id  3/
Sleep 1.5
# An order in place of the throw.
Type "j"
Wait /inject> $/
Sleep 0.5
Type "(first orders)"
Sleep 0.8
Enter
Wait /Ada/
Sleep 2.5
