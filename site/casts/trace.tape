# Tracing a namespace's calls, then timing them.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type ",ns demo.core"
Enter
Wait /demo.core=> $/
Sleep 0.5
Type ",trace demo.core"
Enter
Wait /Tracing/
Sleep 1
Type "(total (find-order 2))"
Enter
Wait /670/
Sleep 2.5
Type ",untrace"
Enter
Wait /Stopped tracing/
Sleep 0.8
Ctrl+L
Sleep 0.5
Type ",profile price total"
Enter
Wait /Profiling demo.core\/total/
Sleep 0.8
Type "(dotimes [_ 1000] (total (find-order 2)))"
Enter
Wait /nil/
Sleep 0.8
Type ",profile"
Enter
Wait /demo.core\/total/
Sleep 3
