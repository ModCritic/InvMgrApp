## Mozilla's list of trusted certificate authorities

The file lib/security/cacerts in this Java runtime is the list of certificate authorities it
trusts. Eclipse Temurin builds it from Mozilla's certdata.txt, which is under the Mozilla Public
License, version 2.0 (https://mozilla.org/MPL/2.0/), and so is the list built from it. The other
licenses of this runtime do not apply to it.

Its source, security/certdata.txt in https://github.com/adoptium/temurin-build at commit
1cf43d7a3e77886bfea7d550a902e151a87d724d, is in the third-party source zip on the release page
this download came from: https://github.com/ModCritic/InvMgrApp/releases
