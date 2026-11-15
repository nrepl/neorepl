# An exception, its stack trace, and browsing it.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type ",ns demo.core"
Enter
Wait /demo.core=> $/
Sleep 0.5
Type "(find-order 42)"
Enter
Wait /No order 42/
Sleep 2
Type ",pst"
Enter
Wait /NO_SOURCE_FILE/
Sleep 2.5
Type ",stacktrace"
Enter
Wait /quit/
Sleep 1.5
# Every frame, the JVM's too, and back to the project's.
Type "a"
Sleep 2
Type "a"
Sleep 1
Down
Sleep 0.5
Down
Sleep 1
Type "d"
Sleep 2.5
Type "q"
Sleep 1
Type "q"
Wait /demo.core=> $/
Sleep 1.5
