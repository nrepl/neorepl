# Stepping through a function with cider-nrepl's debugger, and CIDER's keys.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type ",ns demo.core"
Enter
Wait /demo.core=> $/
Sleep 0.5
Type ",break price"
Enter
Wait /stops at each step now/
Sleep 1.5
Type "(total (find-order 2))"
Enter
Wait /debug> $/
Sleep 2
# The next step, the menu, the drink, and the drink's price.
Type "n"
Wait /debug> $/
Sleep 1.8
Type "n"
Wait /debug> $/
Sleep 1.2
Type "n"
Wait /debug> $/
Sleep 1.5
Type "l"
Wait /qty/
Sleep 2
# On to the cold brew's price, then past every breakpoint.
Type "c"
Wait /debug> $/
Sleep 1.5
Type "C"
Wait /670/
Sleep 1.2
Type ",unbreak"
Enter
Wait /runs without stopping again/
Sleep 2
