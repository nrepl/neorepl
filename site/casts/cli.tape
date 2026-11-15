# The same server from the shell, for scripts and coding agents.

Wait /\$ $/
Type "neorepl eval '(+ 1 2)'"
Enter
Wait /\$ $/
Sleep 1
Type "neorepl doc demo.core/total"
Enter
Wait /\$ $/
Sleep 1.5
Type "neorepl eval --format json '(demo.core/find-order 1)'"
Enter
Wait /\$ $/
Sleep 2
Type "neorepl complete demo.core/f"
Enter
Wait /\$ $/
Sleep 1.5
Type "neorepl test"
Enter
Wait /\$ $/
Sleep 1
Type "echo $?"
Enter
Wait /\$ $/
Sleep 3
