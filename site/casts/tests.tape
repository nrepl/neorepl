# A test that fails, the fix, and the rerun.

Wait /\$ $/
Type "neorepl"
Enter
Wait /user=> $/
Sleep 0.6
Type "(require 'demo.core)"
Enter
Wait /nil/
Sleep 0.4
Type "(demo.core/format-cents 380)"
Enter
Wait /3\.00/
Sleep 1.2
Type ",test demo.core-test"
Enter
Wait /failures?, \d+ errors?/
Sleep 3
# The fix, in an editor somewhere.
Shell "perl -pi -e 's/\\(rem cents 10\\)/(rem cents 100)/' src/demo/core.clj"
Type ",reload"
Enter
Wait /Reloaded/
Sleep 1
Type ",retest"
Enter
Wait /0 failures/
Sleep 1.5
Type "(demo.core/format-cents 380)"
Enter
Wait /3\.80/
Sleep 3
