# The inspector, full-screen: into a value and back out.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type "(require 'demo.core)"
Enter
Wait /nil/
Sleep 0.4
Type ",inspect demo.core/orders"
Enter
Wait /Class/
Sleep 1.5

# Rich's order, then its items, then one of them.
Down
Sleep 0.8
Enter
Wait /Rich/
Sleep 1.5
Down
Sleep 0.25
Down
Sleep 0.25
Down
Sleep 0.25
Down
Sleep 0.25
Down
Sleep 0.25
Down
Sleep 0.25
Down
Sleep 0.8
Enter
Wait /cold-brew/
Sleep 1.2
Down
Sleep 0.6
Enter
Wait /:qty = 1/
Sleep 2

# And back.
Left
Sleep 0.7
Left
Sleep 0.7
Left
Sleep 1.2
Type "q"
Wait /user=> $/
Sleep 1.5
