# Documentation and source, by name, at the cursor, and by search.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type ",ns demo.core"
Enter
Wait /demo.core=> $/
Sleep 0.6

Type ",doc total"
Enter
Wait /What an order comes to/
Sleep 1.5
Type ",source price"
Enter
Wait /get-in menu/
Sleep 1.5
Type ",where total"
Enter
Wait /core\.clj/
Sleep 1.5

# C-x C-d shows the documentation of what's at the cursor.
Type "(str/join "
Sleep 0.6
Ctrl+X Ctrl+D
Wait /Returns a string/
Sleep 2.5
Ctrl+U
Sleep 0.4

Type ",apropos string/s"
Enter
Wait /starts-with/
Sleep 3
